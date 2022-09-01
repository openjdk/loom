/*
 * Copyright (c) 1997, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, Azul Systems, Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "jvm.h"
#include "cds/metaspaceShared.hpp"
#include "classfile/classLoader.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/javaThreadStatus.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmClasses.hpp"
#include "classfile/vmSymbols.hpp"
#include "compiler/compileBroker.hpp"
#include "compiler/compileTask.hpp"
#include "compiler/compilerThread.hpp"
#include "gc/shared/barrierSet.hpp"
#include "gc/shared/barrierSetNMethod.hpp"
#include "gc/shared/gcVMOperations.hpp"
#include "gc/shared/oopStorage.hpp"
#include "gc/shared/oopStorageSet.hpp"
#include "gc/shared/stringdedup/stringDedup.hpp"
#include "jfr/jfrEvents.hpp"
#include "jvmtifiles/jvmtiEnv.hpp"
#include "logging/log.hpp"
#include "logging/logAsyncWriter.hpp"
#include "logging/logConfiguration.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/iterator.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/klass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/symbol.hpp"
#include "prims/jvm_misc.hpp"
#include "runtime/arguments.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/flags/jvmFlagLimit.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/java.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/javaThread.inline.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/jniPeriodicChecker.hpp"
#include "runtime/monitorDeflationThread.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/nonJavaThread.hpp"
#include "runtime/objectMonitor.hpp"
#include "runtime/osThread.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/safepointMechanism.inline.hpp"
#include "runtime/safepointVerifiers.hpp"
#include "runtime/serviceThread.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/statSampler.hpp"
#include "runtime/stubCodeGenerator.hpp"
#include "runtime/thread.inline.hpp"
#include "services/threadIdTable.hpp"
#include "runtime/threads.hpp"
#include "runtime/threadSMR.inline.hpp"
#include "runtime/timer.hpp"
#include "runtime/timerTrace.hpp"
#include "runtime/vmOperations.hpp"
#include "runtime/vm_version.hpp"
#include "services/attachListener.hpp"
#include "services/management.hpp"
#include "services/memTracker.hpp"
#include "services/threadService.hpp"
#include "utilities/dtrace.hpp"
#include "utilities/events.hpp"
#include "utilities/macros.hpp"
#include "utilities/vmError.hpp"
#if INCLUDE_JVMCI
#include "jvmci/jvmci.hpp"
#include "jvmci/jvmciEnv.hpp"
#endif
#ifdef COMPILER2
#include "opto/idealGraphPrinter.hpp"
#endif
#if INCLUDE_RTM_OPT
#include "runtime/rtmLocking.hpp"
#endif
#if INCLUDE_JFR
#include "jfr/jfr.hpp"
#endif

// Initialization after module runtime initialization
void universe_post_module_init();  // must happen after call_initPhase2


static void initialize_class(Symbol* class_name, TRAPS) {
  Klass* klass = SystemDictionary::resolve_or_fail(class_name, true, CHECK);
  InstanceKlass::cast(klass)->initialize(CHECK);
}


// Creates the initial ThreadGroup
static Handle create_initial_thread_group(TRAPS) {
  Handle system_instance = JavaCalls::construct_new_instance(
                            vmClasses::ThreadGroup_klass(),
                            vmSymbols::void_method_signature(),
                            CHECK_NH);
  Universe::set_system_thread_group(system_instance());

  Handle string = java_lang_String::create_from_str("main", CHECK_NH);
  Handle main_instance = JavaCalls::construct_new_instance(
                            vmClasses::ThreadGroup_klass(),
                            vmSymbols::threadgroup_string_void_signature(),
                            system_instance,
                            string,
                            CHECK_NH);
  return main_instance;
}

// Creates the initial Thread, and sets it to running.
static void create_initial_thread(Handle thread_group, JavaThread* thread,
                                 TRAPS) {
  InstanceKlass* ik = vmClasses::Thread_klass();
  assert(ik->is_initialized(), "must be");
  instanceHandle thread_oop = ik->allocate_instance_handle(CHECK);

  // Cannot use JavaCalls::construct_new_instance because the java.lang.Thread
  // constructor calls Thread.current(), which must be set here for the
  // initial thread.
  java_lang_Thread::set_thread(thread_oop(), thread);
  thread->set_threadOopHandles(thread_oop());

  Handle string = java_lang_String::create_from_str("main", CHECK);

  JavaValue result(T_VOID);
  JavaCalls::call_special(&result, thread_oop,
                          ik,
                          vmSymbols::object_initializer_name(),
                          vmSymbols::threadgroup_string_void_signature(),
                          thread_group,
                          string,
                          CHECK);

  // Set thread status to running since main thread has
  // been started and running.
  java_lang_Thread::set_thread_status(thread_oop(),
                                      JavaThreadStatus::RUNNABLE);
}

// Extract version and vendor specific information from
// java.lang.VersionProps fields.
// Returned char* is allocated in the thread's resource area
// so must be copied for permanency.
static const char* get_java_version_info(InstanceKlass* ik,
                                         Symbol* field_name) {
  fieldDescriptor fd;
  bool found = ik != NULL &&
               ik->find_local_field(field_name,
                                    vmSymbols::string_signature(), &fd);
  if (found) {
    oop name_oop = ik->java_mirror()->obj_field(fd.offset());
    if (name_oop == NULL) {
      return NULL;
    }
    const char* name = java_lang_String::as_utf8_string(name_oop);
    return name;
  } else {
    return NULL;
  }
}

// ======= Threads ========

// The Threads class links together all active threads, and provides
// operations over all threads. It is protected by the Threads_lock,
// which is also used in other global contexts like safepointing.
// ThreadsListHandles are used to safely perform operations on one
// or more threads without the risk of the thread exiting during the
// operation.
//
// Note: The Threads_lock is currently more widely used than we
// would like. We are actively migrating Threads_lock uses to other
// mechanisms in order to reduce Threads_lock contention.

int         Threads::_number_of_threads = 0;
int         Threads::_number_of_non_daemon_threads = 0;
int         Threads::_return_code = 0;
uintx       Threads::_thread_claim_token = 1; // Never zero.

#ifdef ASSERT
bool        Threads::_vm_complete = false;
#endif

// General purpose hook into Java code, run once when the VM is initialized.
// The Java library method itself may be changed independently from the VM.
static void call_postVMInitHook(TRAPS) {
  Klass* klass = SystemDictionary::resolve_or_null(vmSymbols::jdk_internal_vm_PostVMInitHook(), THREAD);
  if (klass != NULL) {
    JavaValue result(T_VOID);
    JavaCalls::call_static(&result, klass, vmSymbols::run_method_name(),
                           vmSymbols::void_method_signature(),
                           CHECK);
  }
}

// All NonJavaThreads (i.e., every non-JavaThread in the system).
void Threads::non_java_threads_do(ThreadClosure* tc) {
  NoSafepointVerifier nsv;
  for (NonJavaThread::Iterator njti; !njti.end(); njti.step()) {
    tc->do_thread(njti.current());
  }
}

// All JavaThreads
#define ALL_JAVA_THREADS(X) \
  for (JavaThread* X : *ThreadsSMRSupport::get_java_thread_list())

// All JavaThreads
void Threads::java_threads_do(ThreadClosure* tc) {
  assert_locked_or_safepoint(Threads_lock);
  // ALL_JAVA_THREADS iterates through all JavaThreads.
  ALL_JAVA_THREADS(p) {
    tc->do_thread(p);
  }
}

void Threads::java_threads_and_vm_thread_do(ThreadClosure* tc) {
  assert_locked_or_safepoint(Threads_lock);
  java_threads_do(tc);
  tc->do_thread(VMThread::vm_thread());
}

// All JavaThreads + all non-JavaThreads (i.e., every thread in the system).
void Threads::threads_do(ThreadClosure* tc) {
  assert_locked_or_safepoint(Threads_lock);
  java_threads_do(tc);
  non_java_threads_do(tc);
}

void Threads::possibly_parallel_threads_do(bool is_par, ThreadClosure* tc) {
  uintx claim_token = Threads::thread_claim_token();
  ALL_JAVA_THREADS(p) {
    if (p->claim_threads_do(is_par, claim_token)) {
      tc->do_thread(p);
    }
  }
  VMThread* vmt = VMThread::vm_thread();
  if (vmt->claim_threads_do(is_par, claim_token)) {
    tc->do_thread(vmt);
  }
}

// The system initialization in the library has three phases.
//
// Phase 1: java.lang.System class initialization
//     java.lang.System is a primordial class loaded and initialized
//     by the VM early during startup.  java.lang.System.<clinit>
//     only does registerNatives and keeps the rest of the class
//     initialization work later until thread initialization completes.
//
//     System.initPhase1 initializes the system properties, the static
//     fields in, out, and err. Set up java signal handlers, OS-specific
//     system settings, and thread group of the main thread.
static void call_initPhase1(TRAPS) {
  Klass* klass = vmClasses::System_klass();
  JavaValue result(T_VOID);
  JavaCalls::call_static(&result, klass, vmSymbols::initPhase1_name(),
                                         vmSymbols::void_method_signature(), CHECK);
}

// Phase 2. Module system initialization
//     This will initialize the module system.  Only java.base classes
//     can be loaded until phase 2 completes.
//
//     Call System.initPhase2 after the compiler initialization and jsr292
//     classes get initialized because module initialization runs a lot of java
//     code, that for performance reasons, should be compiled.  Also, this will
//     enable the startup code to use lambda and other language features in this
//     phase and onward.
//
//     After phase 2, The VM will begin search classes from -Xbootclasspath/a.
static void call_initPhase2(TRAPS) {
  TraceTime timer("Initialize module system", TRACETIME_LOG(Info, startuptime));

  Klass* klass = vmClasses::System_klass();

  JavaValue result(T_INT);
  JavaCallArguments args;
  args.push_int(DisplayVMOutputToStderr);
  args.push_int(log_is_enabled(Debug, init)); // print stack trace if exception thrown
  JavaCalls::call_static(&result, klass, vmSymbols::initPhase2_name(),
                                         vmSymbols::boolean_boolean_int_signature(), &args, CHECK);
  if (result.get_jint() != JNI_OK) {
    vm_exit_during_initialization(); // no message or exception
  }

  universe_post_module_init();
}

// Phase 3. final setup - set security manager, system class loader and TCCL
//
//     This will instantiate and set the security manager, set the system class
//     loader as well as the thread context class loader.  The security manager
//     and system class loader may be a custom class loaded from -Xbootclasspath/a,
//     other modules or the application's classpath.
static void call_initPhase3(TRAPS) {
  Klass* klass = vmClasses::System_klass();
  JavaValue result(T_VOID);
  JavaCalls::call_static(&result, klass, vmSymbols::initPhase3_name(),
                                         vmSymbols::void_method_signature(), CHECK);
}

void Threads::initialize_java_lang_classes(JavaThread* main_thread, TRAPS) {
  TraceTime timer("Initialize java.lang classes", TRACETIME_LOG(Info, startuptime));

  if (EagerXrunInit && Arguments::init_libraries_at_startup()) {
    create_vm_init_libraries();
  }

  initialize_class(vmSymbols::java_lang_String(), CHECK);

  // Inject CompactStrings value after the static initializers for String ran.
  java_lang_String::set_compact_strings(CompactStrings);

  // Initialize java_lang.System (needed before creating the thread)
  initialize_class(vmSymbols::java_lang_System(), CHECK);
  // The VM creates & returns objects of this class. Make sure it's initialized.
  initialize_class(vmSymbols::java_lang_Class(), CHECK);
  initialize_class(vmSymbols::java_lang_ThreadGroup(), CHECK);
  Handle thread_group = create_initial_thread_group(CHECK);
  Universe::set_main_thread_group(thread_group());
  initialize_class(vmSymbols::java_lang_Thread(), CHECK);
  create_initial_thread(thread_group, main_thread, CHECK);

  // The VM creates objects of this class.
  initialize_class(vmSymbols::java_lang_Module(), CHECK);

#ifdef ASSERT
  InstanceKlass *k = vmClasses::UnsafeConstants_klass();
  assert(k->is_not_initialized(), "UnsafeConstants should not already be initialized");
#endif

  // initialize the hardware-specific constants needed by Unsafe
  initialize_class(vmSymbols::jdk_internal_misc_UnsafeConstants(), CHECK);
  jdk_internal_misc_UnsafeConstants::set_unsafe_constants();

  // The VM preresolves methods to these classes. Make sure that they get initialized
  initialize_class(vmSymbols::java_lang_reflect_Method(), CHECK);
  initialize_class(vmSymbols::java_lang_ref_Finalizer(), CHECK);

  // Phase 1 of the system initialization in the library, java.lang.System class initialization
  call_initPhase1(CHECK);

  // Get the Java runtime name, version, and vendor info after java.lang.System is initialized.
  // Some values are actually configure-time constants but some can be set via the jlink tool and
  // so must be read dynamically. We treat them all the same.
  InstanceKlass* ik = SystemDictionary::find_instance_klass(vmSymbols::java_lang_VersionProps(),
                                                            Handle(), Handle());
  {
    ResourceMark rm(main_thread);
    JDK_Version::set_java_version(get_java_version_info(ik, vmSymbols::java_version_name()));

    JDK_Version::set_runtime_name(get_java_version_info(ik, vmSymbols::java_runtime_name_name()));

    JDK_Version::set_runtime_version(get_java_version_info(ik, vmSymbols::java_runtime_version_name()));

    JDK_Version::set_runtime_vendor_version(get_java_version_info(ik, vmSymbols::java_runtime_vendor_version_name()));

    JDK_Version::set_runtime_vendor_vm_bug_url(get_java_version_info(ik, vmSymbols::java_runtime_vendor_vm_bug_url_name()));
  }

  // an instance of OutOfMemory exception has been allocated earlier
  initialize_class(vmSymbols::java_lang_OutOfMemoryError(), CHECK);
  initialize_class(vmSymbols::java_lang_NullPointerException(), CHECK);
  initialize_class(vmSymbols::java_lang_ClassCastException(), CHECK);
  initialize_class(vmSymbols::java_lang_ArrayStoreException(), CHECK);
  initialize_class(vmSymbols::java_lang_ArithmeticException(), CHECK);
  initialize_class(vmSymbols::java_lang_StackOverflowError(), CHECK);
  initialize_class(vmSymbols::java_lang_IllegalMonitorStateException(), CHECK);
  initialize_class(vmSymbols::java_lang_IllegalArgumentException(), CHECK);
}

void Threads::initialize_jsr292_core_classes(TRAPS) {
  TraceTime timer("Initialize java.lang.invoke classes", TRACETIME_LOG(Info, startuptime));

  initialize_class(vmSymbols::java_lang_invoke_MethodHandle(), CHECK);
  initialize_class(vmSymbols::java_lang_invoke_ResolvedMethodName(), CHECK);
  initialize_class(vmSymbols::java_lang_invoke_MemberName(), CHECK);
  initialize_class(vmSymbols::java_lang_invoke_MethodHandleNatives(), CHECK);
}

jint Threads::create_vm(JavaVMInitArgs* args, bool* canTryAgain) {
  extern void JDK_Version_init();

  // Preinitialize version info.
  VM_Version::early_initialize();

  // Check version
  if (!is_supported_jni_version(args->version)) return JNI_EVERSION;

  // Initialize library-based TLS
  ThreadLocalStorage::init();

  // Initialize the output stream module
  ostream_init();

  // Process java launcher properties.
  Arguments::process_sun_java_launcher_properties(args);

  // Initialize the os module
  os::init();

  MACOS_AARCH64_ONLY(os::current_thread_enable_wx(WXWrite));

  // Record VM creation timing statistics
  TraceVmCreationTime create_vm_timer;
  create_vm_timer.start();

  // Initialize system properties.
  Arguments::init_system_properties();

  // So that JDK version can be used as a discriminator when parsing arguments
  JDK_Version_init();

  // Update/Initialize System properties after JDK version number is known
  Arguments::init_version_specific_system_properties();

  // Make sure to initialize log configuration *before* parsing arguments
  LogConfiguration::initialize(create_vm_timer.begin_time());

  // Parse arguments
  // Note: this internally calls os::init_container_support()
  jint parse_result = Arguments::parse(args);
  if (parse_result != JNI_OK) return parse_result;

  // Initialize NMT right after argument parsing to keep the pre-NMT-init window small.
  MemTracker::initialize();

  os::init_before_ergo();

  jint ergo_result = Arguments::apply_ergo();
  if (ergo_result != JNI_OK) return ergo_result;

  // Final check of all ranges after ergonomics which may change values.
  if (!JVMFlagLimit::check_all_ranges()) {
    return JNI_EINVAL;
  }

  // Final check of all 'AfterErgo' constraints after ergonomics which may change values.
  bool constraint_result = JVMFlagLimit::check_all_constraints(JVMFlagConstraintPhase::AfterErgo);
  if (!constraint_result) {
    return JNI_EINVAL;
  }

  if (PauseAtStartup) {
    os::pause();
  }

  HOTSPOT_VM_INIT_BEGIN();

  // Timing (must come after argument parsing)
  TraceTime timer("Create VM", TRACETIME_LOG(Info, startuptime));

  // Initialize the os module after parsing the args
  jint os_init_2_result = os::init_2();
  if (os_init_2_result != JNI_OK) return os_init_2_result;

#ifdef CAN_SHOW_REGISTERS_ON_ASSERT
  // Initialize assert poison page mechanism.
  if (ShowRegistersOnAssert) {
    initialize_assert_poison();
  }
#endif // CAN_SHOW_REGISTERS_ON_ASSERT

  SafepointMechanism::initialize();

  jint adjust_after_os_result = Arguments::adjust_after_os();
  if (adjust_after_os_result != JNI_OK) return adjust_after_os_result;

  // Initialize output stream logging
  ostream_init_log();

  // Convert -Xrun to -agentlib: if there is no JVM_OnLoad
  // Must be before create_vm_init_agents()
  if (Arguments::init_libraries_at_startup()) {
    convert_vm_init_libraries_to_agents();
  }

  // Launch -agentlib/-agentpath and converted -Xrun agents
  if (Arguments::init_agents_at_startup()) {
    create_vm_init_agents();
  }

  // Initialize Threads state
  _number_of_threads = 0;
  _number_of_non_daemon_threads = 0;

  // Initialize global data structures and create system classes in heap
  vm_init_globals();

#if INCLUDE_JVMCI
  if (JVMCICounterSize > 0) {
    JavaThread::_jvmci_old_thread_counters = NEW_C_HEAP_ARRAY(jlong, JVMCICounterSize, mtJVMCI);
    memset(JavaThread::_jvmci_old_thread_counters, 0, sizeof(jlong) * JVMCICounterSize);
  } else {
    JavaThread::_jvmci_old_thread_counters = NULL;
  }
#endif // INCLUDE_JVMCI

  // Initialize OopStorage for threadObj
  JavaThread::_thread_oop_storage = OopStorageSet::create_strong("Thread OopStorage", mtThread);

  // Attach the main thread to this os thread
  JavaThread* main_thread = new JavaThread();
  main_thread->set_thread_state(_thread_in_vm);
  main_thread->initialize_thread_current();
  // must do this before set_active_handles
  main_thread->record_stack_base_and_size();
  main_thread->register_thread_stack_with_NMT();
  main_thread->set_active_handles(JNIHandleBlock::allocate_block());
  MACOS_AARCH64_ONLY(main_thread->init_wx());

  if (!main_thread->set_as_starting_thread()) {
    vm_shutdown_during_initialization(
                                      "Failed necessary internal allocation. Out of swap space");
    main_thread->smr_delete();
    *canTryAgain = false; // don't let caller call JNI_CreateJavaVM again
    return JNI_ENOMEM;
  }

  // Enable guard page *after* os::create_main_thread(), otherwise it would
  // crash Linux VM, see notes in os_linux.cpp.
  main_thread->stack_overflow_state()->create_stack_guard_pages();

  // Initialize Java-Level synchronization subsystem
  ObjectMonitor::Initialize();
  ObjectSynchronizer::initialize();

  // Initialize global modules
  jint status = init_globals();
  if (status != JNI_OK) {
    main_thread->smr_delete();
    *canTryAgain = false; // don't let caller call JNI_CreateJavaVM again
    return status;
  }

  JFR_ONLY(Jfr::on_create_vm_1();)

  // Should be done after the heap is fully created
  main_thread->cache_global_variables();

  { MutexLocker mu(Threads_lock);
    Threads::add(main_thread);
  }

  // Any JVMTI raw monitors entered in onload will transition into
  // real raw monitor. VM is setup enough here for raw monitor enter.
  JvmtiExport::transition_pending_onload_raw_monitors();

  // Create the VMThread
  { TraceTime timer("Start VMThread", TRACETIME_LOG(Info, startuptime));

    VMThread::create();
    VMThread* vmthread = VMThread::vm_thread();

    if (!os::create_thread(vmthread, os::vm_thread)) {
      vm_exit_during_initialization("Cannot create VM thread. "
                                    "Out of system resources.");
    }

    // Wait for the VM thread to become ready, and VMThread::run to initialize
    // Monitors can have spurious returns, must always check another state flag
    {
      MonitorLocker ml(Notify_lock);
      os::start_thread(vmthread);
      while (!vmthread->is_running()) {
        ml.wait();
      }
    }
  }

  assert(Universe::is_fully_initialized(), "not initialized");
  if (VerifyDuringStartup) {
    // Make sure we're starting with a clean slate.
    VM_Verify verify_op;
    VMThread::execute(&verify_op);
  }

  // We need this to update the java.vm.info property in case any flags used
  // to initially define it have been changed. This is needed for both CDS
  // since UseSharedSpaces may be changed after java.vm.info
  // is initially computed. See Abstract_VM_Version::vm_info_string().
  // This update must happen before we initialize the java classes, but
  // after any initialization logic that might modify the flags.
  Arguments::update_vm_info_property(VM_Version::vm_info_string());

  JavaThread* THREAD = JavaThread::current(); // For exception macros.
  HandleMark hm(THREAD);

  // Always call even when there are not JVMTI environments yet, since environments
  // may be attached late and JVMTI must track phases of VM execution
  JvmtiExport::enter_early_start_phase();

  // Notify JVMTI agents that VM has started (JNI is up) - nop if no agents.
  JvmtiExport::post_early_vm_start();

  initialize_java_lang_classes(main_thread, CHECK_JNI_ERR);

  quicken_jni_functions();

  // No more stub generation allowed after that point.
  StubCodeDesc::freeze();

  // Set flag that basic initialization has completed. Used by exceptions and various
  // debug stuff, that does not work until all basic classes have been initialized.
  set_init_completed();

  LogConfiguration::post_initialize();
  Metaspace::post_initialize();

  HOTSPOT_VM_INIT_END();

  // record VM initialization completion time
#if INCLUDE_MANAGEMENT
  Management::record_vm_init_completed();
#endif // INCLUDE_MANAGEMENT

  // Signal Dispatcher needs to be started before VMInit event is posted
  os::initialize_jdk_signal_support(CHECK_JNI_ERR);

  // Start Attach Listener if +StartAttachListener or it can't be started lazily
  if (!DisableAttachMechanism) {
    AttachListener::vm_start();
    if (StartAttachListener || AttachListener::init_at_startup()) {
      AttachListener::init();
    }
  }

  // Launch -Xrun agents
  // Must be done in the JVMTI live phase so that for backward compatibility the JDWP
  // back-end can launch with -Xdebug -Xrunjdwp.
  if (!EagerXrunInit && Arguments::init_libraries_at_startup()) {
    create_vm_init_libraries();
  }

  Chunk::start_chunk_pool_cleaner_task();

  // Start the service thread
  // The service thread enqueues JVMTI deferred events and does various hashtable
  // and other cleanups.  Needs to start before the compilers start posting events.
  ServiceThread::initialize();

  // Start the monitor deflation thread:
  MonitorDeflationThread::initialize();

  // initialize compiler(s)
#if defined(COMPILER1) || COMPILER2_OR_JVMCI
#if INCLUDE_JVMCI
  bool force_JVMCI_intialization = false;
  if (EnableJVMCI) {
    // Initialize JVMCI eagerly when it is explicitly requested.
    // Or when JVMCILibDumpJNIConfig or JVMCIPrintProperties is enabled.
    force_JVMCI_intialization = EagerJVMCI || JVMCIPrintProperties || JVMCILibDumpJNIConfig;

    if (!force_JVMCI_intialization) {
      // 8145270: Force initialization of JVMCI runtime otherwise requests for blocking
      // compilations via JVMCI will not actually block until JVMCI is initialized.
      force_JVMCI_intialization = UseJVMCICompiler && (!UseInterpreter || !BackgroundCompilation);
    }
  }
#endif
  CompileBroker::compilation_init_phase1(CHECK_JNI_ERR);
  // Postpone completion of compiler initialization to after JVMCI
  // is initialized to avoid timeouts of blocking compilations.
  if (JVMCI_ONLY(!force_JVMCI_intialization) NOT_JVMCI(true)) {
    CompileBroker::compilation_init_phase2();
  }
#endif

  // Pre-initialize some JSR292 core classes to avoid deadlock during class loading.
  // It is done after compilers are initialized, because otherwise compilations of
  // signature polymorphic MH intrinsics can be missed
  // (see SystemDictionary::find_method_handle_intrinsic).
  initialize_jsr292_core_classes(CHECK_JNI_ERR);

  // This will initialize the module system.  Only java.base classes can be
  // loaded until phase 2 completes
  call_initPhase2(CHECK_JNI_ERR);

  JFR_ONLY(Jfr::on_create_vm_2();)

  // Always call even when there are not JVMTI environments yet, since environments
  // may be attached late and JVMTI must track phases of VM execution
  JvmtiExport::enter_start_phase();

  // Notify JVMTI agents that VM has started (JNI is up) - nop if no agents.
  JvmtiExport::post_vm_start();

  // Final system initialization including security manager and system class loader
  call_initPhase3(CHECK_JNI_ERR);

  // cache the system and platform class loaders
  SystemDictionary::compute_java_loaders(CHECK_JNI_ERR);

#if INCLUDE_CDS
  // capture the module path info from the ModuleEntryTable
  ClassLoader::initialize_module_path(THREAD);
  if (HAS_PENDING_EXCEPTION) {
    java_lang_Throwable::print(PENDING_EXCEPTION, tty);
    vm_exit_during_initialization("ClassLoader::initialize_module_path() failed unexpectedly");
  }
#endif

#if INCLUDE_JVMCI
  if (force_JVMCI_intialization) {
    JVMCI::initialize_compiler(CHECK_JNI_ERR);
    CompileBroker::compilation_init_phase2();
  }
#endif

  // Always call even when there are not JVMTI environments yet, since environments
  // may be attached late and JVMTI must track phases of VM execution
  JvmtiExport::enter_live_phase();

  // Make perfmemory accessible
  PerfMemory::set_accessible(true);

  // Notify JVMTI agents that VM initialization is complete - nop if no agents.
  JvmtiExport::post_vm_initialized();

  JFR_ONLY(Jfr::on_create_vm_3();)

#if INCLUDE_MANAGEMENT
  Management::initialize(THREAD);

  if (HAS_PENDING_EXCEPTION) {
    // management agent fails to start possibly due to
    // configuration problem and is responsible for printing
    // stack trace if appropriate. Simply exit VM.
    vm_exit(1);
  }
#endif // INCLUDE_MANAGEMENT

  StatSampler::engage();
  if (CheckJNICalls)                  JniPeriodicChecker::engage();

#if INCLUDE_RTM_OPT
  RTMLockingCounters::init();
#endif

  call_postVMInitHook(THREAD);
  // The Java side of PostVMInitHook.run must deal with all
  // exceptions and provide means of diagnosis.
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
  }

  {
    MutexLocker ml(PeriodicTask_lock);
    // Make sure the WatcherThread can be started by WatcherThread::start()
    // or by dynamic enrollment.
    WatcherThread::make_startable();
    // Start up the WatcherThread if there are any periodic tasks
    // NOTE:  All PeriodicTasks should be registered by now. If they
    //   aren't, late joiners might appear to start slowly (we might
    //   take a while to process their first tick).
    if (PeriodicTask::num_tasks() > 0) {
      WatcherThread::start();
    }
  }

  create_vm_timer.end();
#ifdef ASSERT
  _vm_complete = true;
#endif

  if (DumpSharedSpaces) {
    MetaspaceShared::preload_and_dump();
    ShouldNotReachHere();
  }

  return JNI_OK;
}

// type for the Agent_OnLoad and JVM_OnLoad entry points
extern "C" {
  typedef jint (JNICALL *OnLoadEntry_t)(JavaVM *, char *, void *);
}
// Find a command line agent library and return its entry point for
//         -agentlib:  -agentpath:   -Xrun
// num_symbol_entries must be passed-in since only the caller knows the number of symbols in the array.
static OnLoadEntry_t lookup_on_load(AgentLibrary* agent,
                                    const char *on_load_symbols[],
                                    size_t num_symbol_entries) {
  OnLoadEntry_t on_load_entry = NULL;
  void *library = NULL;

  if (!agent->valid()) {
    char buffer[JVM_MAXPATHLEN];
    char ebuf[1024] = "";
    const char *name = agent->name();
    const char *msg = "Could not find agent library ";

    // First check to see if agent is statically linked into executable
    if (os::find_builtin_agent(agent, on_load_symbols, num_symbol_entries)) {
      library = agent->os_lib();
    } else if (agent->is_absolute_path()) {
      library = os::dll_load(name, ebuf, sizeof ebuf);
      if (library == NULL) {
        const char *sub_msg = " in absolute path, with error: ";
        size_t len = strlen(msg) + strlen(name) + strlen(sub_msg) + strlen(ebuf) + 1;
        char *buf = NEW_C_HEAP_ARRAY(char, len, mtThread);
        jio_snprintf(buf, len, "%s%s%s%s", msg, name, sub_msg, ebuf);
        // If we can't find the agent, exit.
        vm_exit_during_initialization(buf, NULL);
        FREE_C_HEAP_ARRAY(char, buf);
      }
    } else {
      // Try to load the agent from the standard dll directory
      if (os::dll_locate_lib(buffer, sizeof(buffer), Arguments::get_dll_dir(),
                             name)) {
        library = os::dll_load(buffer, ebuf, sizeof ebuf);
      }
      if (library == NULL) { // Try the library path directory.
        if (os::dll_build_name(buffer, sizeof(buffer), name)) {
          library = os::dll_load(buffer, ebuf, sizeof ebuf);
        }
        if (library == NULL) {
          const char *sub_msg = " on the library path, with error: ";
          const char *sub_msg2 = "\nModule java.instrument may be missing from runtime image.";

          size_t len = strlen(msg) + strlen(name) + strlen(sub_msg) +
                       strlen(ebuf) + strlen(sub_msg2) + 1;
          char *buf = NEW_C_HEAP_ARRAY(char, len, mtThread);
          if (!agent->is_instrument_lib()) {
            jio_snprintf(buf, len, "%s%s%s%s", msg, name, sub_msg, ebuf);
          } else {
            jio_snprintf(buf, len, "%s%s%s%s%s", msg, name, sub_msg, ebuf, sub_msg2);
          }
          // If we can't find the agent, exit.
          vm_exit_during_initialization(buf, NULL);
          FREE_C_HEAP_ARRAY(char, buf);
        }
      }
    }
    agent->set_os_lib(library);
    agent->set_valid();
  }

  // Find the OnLoad function.
  on_load_entry =
    CAST_TO_FN_PTR(OnLoadEntry_t, os::find_agent_function(agent,
                                                          false,
                                                          on_load_symbols,
                                                          num_symbol_entries));
  return on_load_entry;
}

// Find the JVM_OnLoad entry point
static OnLoadEntry_t lookup_jvm_on_load(AgentLibrary* agent) {
  const char *on_load_symbols[] = JVM_ONLOAD_SYMBOLS;
  return lookup_on_load(agent, on_load_symbols, sizeof(on_load_symbols) / sizeof(char*));
}

// Find the Agent_OnLoad entry point
static OnLoadEntry_t lookup_agent_on_load(AgentLibrary* agent) {
  const char *on_load_symbols[] = AGENT_ONLOAD_SYMBOLS;
  return lookup_on_load(agent, on_load_symbols, sizeof(on_load_symbols) / sizeof(char*));
}

// For backwards compatibility with -Xrun
// Convert libraries with no JVM_OnLoad, but which have Agent_OnLoad to be
// treated like -agentpath:
// Must be called before agent libraries are created
void Threads::convert_vm_init_libraries_to_agents() {
  AgentLibrary* agent;
  AgentLibrary* next;

  for (agent = Arguments::libraries(); agent != NULL; agent = next) {
    next = agent->next();  // cache the next agent now as this agent may get moved off this list
    OnLoadEntry_t on_load_entry = lookup_jvm_on_load(agent);

    // If there is an JVM_OnLoad function it will get called later,
    // otherwise see if there is an Agent_OnLoad
    if (on_load_entry == NULL) {
      on_load_entry = lookup_agent_on_load(agent);
      if (on_load_entry != NULL) {
        // switch it to the agent list -- so that Agent_OnLoad will be called,
        // JVM_OnLoad won't be attempted and Agent_OnUnload will
        Arguments::convert_library_to_agent(agent);
      } else {
        vm_exit_during_initialization("Could not find JVM_OnLoad or Agent_OnLoad function in the library", agent->name());
      }
    }
  }
}

// Create agents for -agentlib:  -agentpath:  and converted -Xrun
// Invokes Agent_OnLoad
// Called very early -- before JavaThreads exist
void Threads::create_vm_init_agents() {
  extern struct JavaVM_ main_vm;
  AgentLibrary* agent;

  JvmtiExport::enter_onload_phase();

  for (agent = Arguments::agents(); agent != NULL; agent = agent->next()) {
    // CDS dumping does not support native JVMTI agent.
    // CDS dumping supports Java agent if the AllowArchivingWithJavaAgent diagnostic option is specified.
    if (Arguments::is_dumping_archive()) {
      if(!agent->is_instrument_lib()) {
        vm_exit_during_cds_dumping("CDS dumping does not support native JVMTI agent, name", agent->name());
      } else if (!AllowArchivingWithJavaAgent) {
        vm_exit_during_cds_dumping(
          "Must enable AllowArchivingWithJavaAgent in order to run Java agent during CDS dumping");
      }
    }

    OnLoadEntry_t  on_load_entry = lookup_agent_on_load(agent);

    if (on_load_entry != NULL) {
      // Invoke the Agent_OnLoad function
      jint err = (*on_load_entry)(&main_vm, agent->options(), NULL);
      if (err != JNI_OK) {
        vm_exit_during_initialization("agent library failed to init", agent->name());
      }
    } else {
      vm_exit_during_initialization("Could not find Agent_OnLoad function in the agent library", agent->name());
    }
  }

  JvmtiExport::enter_primordial_phase();
}

extern "C" {
  typedef void (JNICALL *Agent_OnUnload_t)(JavaVM *);
}

void Threads::shutdown_vm_agents() {
  // Send any Agent_OnUnload notifications
  const char *on_unload_symbols[] = AGENT_ONUNLOAD_SYMBOLS;
  size_t num_symbol_entries = ARRAY_SIZE(on_unload_symbols);
  extern struct JavaVM_ main_vm;
  for (AgentLibrary* agent = Arguments::agents(); agent != NULL; agent = agent->next()) {

    // Find the Agent_OnUnload function.
    Agent_OnUnload_t unload_entry = CAST_TO_FN_PTR(Agent_OnUnload_t,
                                                   os::find_agent_function(agent,
                                                   false,
                                                   on_unload_symbols,
                                                   num_symbol_entries));

    // Invoke the Agent_OnUnload function
    if (unload_entry != NULL) {
      JavaThread* thread = JavaThread::current();
      ThreadToNativeFromVM ttn(thread);
      HandleMark hm(thread);
      (*unload_entry)(&main_vm);
    }
  }
}

// Called for after the VM is initialized for -Xrun libraries which have not been converted to agent libraries
// Invokes JVM_OnLoad
void Threads::create_vm_init_libraries() {
  extern struct JavaVM_ main_vm;
  AgentLibrary* agent;

  for (agent = Arguments::libraries(); agent != NULL; agent = agent->next()) {
    OnLoadEntry_t on_load_entry = lookup_jvm_on_load(agent);

    if (on_load_entry != NULL) {
      // Invoke the JVM_OnLoad function
      JavaThread* thread = JavaThread::current();
      ThreadToNativeFromVM ttn(thread);
      HandleMark hm(thread);
      jint err = (*on_load_entry)(&main_vm, agent->options(), NULL);
      if (err != JNI_OK) {
        vm_exit_during_initialization("-Xrun library failed to init", agent->name());
      }
    } else {
      vm_exit_during_initialization("Could not find JVM_OnLoad function in -Xrun library", agent->name());
    }
  }
}

// Threads::destroy_vm() is normally called from jni_DestroyJavaVM() when
// the program falls off the end of main(). Another VM exit path is through
// vm_exit() when the program calls System.exit() to return a value or when
// there is a serious error in VM. The two shutdown paths are not exactly
// the same, but they share Shutdown.shutdown() at Java level and before_exit()
// and VM_Exit op at VM level.
//
// Shutdown sequence:
//   + Shutdown native memory tracking if it is on
//   + Wait until we are the last non-daemon thread to execute
//     <-- every thing is still working at this moment -->
//   + Call java.lang.Shutdown.shutdown(), which will invoke Java level
//        shutdown hooks
//   + Call before_exit(), prepare for VM exit
//      > run VM level shutdown hooks (they are registered through JVM_OnExit(),
//        currently the only user of this mechanism is File.deleteOnExit())
//      > stop StatSampler, watcher thread,
//        post thread end and vm death events to JVMTI,
//        stop signal thread
//   + Call JavaThread::exit(), it will:
//      > release JNI handle blocks, remove stack guard pages
//      > remove this thread from Threads list
//     <-- no more Java code from this thread after this point -->
//   + Stop VM thread, it will bring the remaining VM to a safepoint and stop
//     the compiler threads at safepoint
//     <-- do not use anything that could get blocked by Safepoint -->
//   + Disable tracing at JNI/JVM barriers
//   + Set _vm_exited flag for threads that are still running native code
//   + Call exit_globals()
//      > deletes tty
//      > deletes PerfMemory resources
//   + Delete this thread
//   + Return to caller

void Threads::destroy_vm() {
  JavaThread* thread = JavaThread::current();

#ifdef ASSERT
  _vm_complete = false;
#endif
  // Wait until we are the last non-daemon thread to execute, or
  // if we are a daemon then wait until the last non-daemon thread has
  // executed.
  bool daemon = java_lang_Thread::is_daemon(thread->threadObj());
  int expected = daemon ? 0 : 1;
  {
    MonitorLocker nu(Threads_lock);
    while (Threads::number_of_non_daemon_threads() > expected)
      // This wait should make safepoint checks, wait without a timeout.
      nu.wait(0);
  }

  EventShutdown e;
  if (e.should_commit()) {
    e.set_reason("No remaining non-daemon Java threads");
    e.commit();
  }

  // Hang forever on exit if we are reporting an error.
  if (ShowMessageBoxOnError && VMError::is_error_reported()) {
    os::infinite_sleep();
  }
  os::wait_for_keypress_at_exit();

  // run Java level shutdown hooks
  thread->invoke_shutdown_hooks();

  before_exit(thread);

  thread->exit(true);

  // We are no longer on the main thread list but could still be in a
  // secondary list where another thread may try to interact with us.
  // So wait until all such interactions are complete before we bring
  // the VM to the termination safepoint. Normally this would be done
  // using thread->smr_delete() below where we delete the thread, but
  // we can't call that after the termination safepoint is active as
  // we will deadlock on the Threads_lock. Once all interactions are
  // complete it is safe to directly delete the thread at any time.
  ThreadsSMRSupport::wait_until_not_protected(thread);

  // Stop VM thread.
  {
    // 4945125 The vm thread comes to a safepoint during exit.
    // GC vm_operations can get caught at the safepoint, and the
    // heap is unparseable if they are caught. Grab the Heap_lock
    // to prevent this. The GC vm_operations will not be able to
    // queue until after the vm thread is dead. After this point,
    // we'll never emerge out of the safepoint before the VM exits.
    // Assert that the thread is terminated so that acquiring the
    // Heap_lock doesn't cause the terminated thread to participate in
    // the safepoint protocol.

    assert(thread->is_terminated(), "must be terminated here");
    MutexLocker ml(Heap_lock);

    VMThread::wait_for_vm_thread_exit();
    assert(SafepointSynchronize::is_at_safepoint(), "VM thread should exit at Safepoint");
    VMThread::destroy();
  }

  // Now, all Java threads are gone except daemon threads. Daemon threads
  // running Java code or in VM are stopped by the Safepoint. However,
  // daemon threads executing native code are still running.  But they
  // will be stopped at native=>Java/VM barriers. Note that we can't
  // simply kill or suspend them, as it is inherently deadlock-prone.

  VM_Exit::set_vm_exited();

  // Clean up ideal graph printers after the VMThread has started
  // the final safepoint which will block all the Compiler threads.
  // Note that this Thread has already logically exited so the
  // clean_up() function's use of a JavaThreadIteratorWithHandle
  // would be a problem except set_vm_exited() has remembered the
  // shutdown thread which is granted a policy exception.
#if defined(COMPILER2) && !defined(PRODUCT)
  IdealGraphPrinter::clean_up();
#endif

  notify_vm_shutdown();

  // exit_globals() will delete tty
  exit_globals();

  // Deleting the shutdown thread here is safe. See comment on
  // wait_until_not_protected() above.
  delete thread;

#if INCLUDE_JVMCI
  if (JVMCICounterSize > 0) {
    FREE_C_HEAP_ARRAY(jlong, JavaThread::_jvmci_old_thread_counters);
  }
#endif

  LogConfiguration::finalize();
}


jboolean Threads::is_supported_jni_version_including_1_1(jint version) {
  if (version == JNI_VERSION_1_1) return JNI_TRUE;
  return is_supported_jni_version(version);
}


jboolean Threads::is_supported_jni_version(jint version) {
  if (version == JNI_VERSION_1_2) return JNI_TRUE;
  if (version == JNI_VERSION_1_4) return JNI_TRUE;
  if (version == JNI_VERSION_1_6) return JNI_TRUE;
  if (version == JNI_VERSION_1_8) return JNI_TRUE;
  if (version == JNI_VERSION_9) return JNI_TRUE;
  if (version == JNI_VERSION_10) return JNI_TRUE;
  if (version == JNI_VERSION_19) return JNI_TRUE;
  return JNI_FALSE;
}

void Threads::add(JavaThread* p, bool force_daemon) {
  // The threads lock must be owned at this point
  assert(Threads_lock->owned_by_self(), "must have threads lock");

  BarrierSet::barrier_set()->on_thread_attach(p);

  // Once a JavaThread is added to the Threads list, smr_delete() has
  // to be used to delete it. Otherwise we can just delete it directly.
  p->set_on_thread_list();

  _number_of_threads++;
  oop threadObj = p->threadObj();
  bool daemon = true;
  // Bootstrapping problem: threadObj can be null for initial
  // JavaThread (or for threads attached via JNI)
  if (!force_daemon &&
      (threadObj == nullptr || !java_lang_Thread::is_daemon(threadObj))) {
    _number_of_non_daemon_threads++;
    daemon = false;
  }

  ThreadService::add_thread(p, daemon);

  // Maintain fast thread list
  ThreadsSMRSupport::add_thread(p);

  // Increase the ObjectMonitor ceiling for the new thread.
  ObjectSynchronizer::inc_in_use_list_ceiling();

  // Possible GC point.
  Events::log(p, "Thread added: " INTPTR_FORMAT, p2i(p));

  // Make new thread known to active EscapeBarrier
  EscapeBarrier::thread_added(p);
}

void Threads::remove(JavaThread* p, bool is_daemon) {
  // Extra scope needed for Thread_lock, so we can check
  // that we do not remove thread without safepoint code notice
  { MonitorLocker ml(Threads_lock);

    if (ThreadIdTable::is_initialized()) {
      // This cleanup must be done before the current thread's GC barrier
      // is detached since we need to touch the threadObj oop.
      jlong tid = SharedRuntime::get_java_tid(p);
      ThreadIdTable::remove_thread(tid);
    }

    // BarrierSet state must be destroyed after the last thread transition
    // before the thread terminates. Thread transitions result in calls to
    // StackWatermarkSet::on_safepoint(), which performs GC processing,
    // requiring the GC state to be alive.
    BarrierSet::barrier_set()->on_thread_detach(p);
    if (p->is_exiting()) {
      // If we got here via JavaThread::exit(), then we remember that the
      // thread's GC barrier has been detached. We don't do this when we get
      // here from another path, e.g., cleanup_failed_attach_current_thread().
      p->set_terminated(JavaThread::_thread_gc_barrier_detached);
    }

    assert(ThreadsSMRSupport::get_java_thread_list()->includes(p), "p must be present");

    // Maintain fast thread list
    ThreadsSMRSupport::remove_thread(p);

    _number_of_threads--;
    if (!is_daemon) {
      _number_of_non_daemon_threads--;

      // If this is the last non-daemon thread then we need to do
      // a notify on the Threads_lock so a thread waiting
      // on destroy_vm will wake up. But that thread could be a daemon
      // or non-daemon, so we notify for both the 0 and 1 case.
      if (number_of_non_daemon_threads() <= 1) {
        ml.notify_all();
      }
    }
    ThreadService::remove_thread(p, is_daemon);

    // Make sure that safepoint code disregard this thread. This is needed since
    // the thread might mess around with locks after this point. This can cause it
    // to do callbacks into the safepoint code. However, the safepoint code is not aware
    // of this thread since it is removed from the queue.
    p->set_terminated(JavaThread::_thread_terminated);

    // Notify threads waiting in EscapeBarriers
    EscapeBarrier::thread_removed(p);
  } // unlock Threads_lock

  // Reduce the ObjectMonitor ceiling for the exiting thread.
  ObjectSynchronizer::dec_in_use_list_ceiling();

  // Since Events::log uses a lock, we grab it outside the Threads_lock
  Events::log(p, "Thread exited: " INTPTR_FORMAT, p2i(p));
}

// Operations on the Threads list for GC.  These are not explicitly locked,
// but the garbage collector must provide a safe context for them to run.
// In particular, these things should never be called when the Threads_lock
// is held by some other thread. (Note: the Safepoint abstraction also
// uses the Threads_lock to guarantee this property. It also makes sure that
// all threads gets blocked when exiting or starting).

void Threads::oops_do(OopClosure* f, CodeBlobClosure* cf) {
  ALL_JAVA_THREADS(p) {
    p->oops_do(f, cf);
  }
  VMThread::vm_thread()->oops_do(f, cf);
}

void Threads::change_thread_claim_token() {
  if (++_thread_claim_token == 0) {
    // On overflow of the token counter, there is a risk of future
    // collisions between a new global token value and a stale token
    // for a thread, because not all iterations visit all threads.
    // (Though it's pretty much a theoretical concern for non-trivial
    // token counter sizes.)  To deal with the possibility, reset all
    // the thread tokens to zero on global token overflow.
    struct ResetClaims : public ThreadClosure {
      virtual void do_thread(Thread* t) {
        t->claim_threads_do(false, 0);
      }
    } reset_claims;
    Threads::threads_do(&reset_claims);
    // On overflow, update the global token to non-zero, to
    // avoid the special "never claimed" initial thread value.
    _thread_claim_token = 1;
  }
}

#ifdef ASSERT
void assert_thread_claimed(const char* kind, Thread* t, uintx expected) {
  const uintx token = t->threads_do_token();
  assert(token == expected,
         "%s " PTR_FORMAT " has incorrect value " UINTX_FORMAT " != "
         UINTX_FORMAT, kind, p2i(t), token, expected);
}

void Threads::assert_all_threads_claimed() {
  ALL_JAVA_THREADS(p) {
    assert_thread_claimed("Thread", p, _thread_claim_token);
  }
  assert_thread_claimed("VMThread", VMThread::vm_thread(), _thread_claim_token);
}
#endif // ASSERT

class ParallelOopsDoThreadClosure : public ThreadClosure {
private:
  OopClosure* _f;
  CodeBlobClosure* _cf;
public:
  ParallelOopsDoThreadClosure(OopClosure* f, CodeBlobClosure* cf) : _f(f), _cf(cf) {}
  void do_thread(Thread* t) {
    t->oops_do(_f, _cf);
  }
};

void Threads::possibly_parallel_oops_do(bool is_par, OopClosure* f, CodeBlobClosure* cf) {
  ParallelOopsDoThreadClosure tc(f, cf);
  possibly_parallel_threads_do(is_par, &tc);
}

void Threads::metadata_do(MetadataClosure* f) {
  ALL_JAVA_THREADS(p) {
    p->metadata_do(f);
  }
}

class ThreadHandlesClosure : public ThreadClosure {
  void (*_f)(Metadata*);
 public:
  ThreadHandlesClosure(void f(Metadata*)) : _f(f) {}
  virtual void do_thread(Thread* thread) {
    thread->metadata_handles_do(_f);
  }
};

void Threads::metadata_handles_do(void f(Metadata*)) {
  // Only walk the Handles in Thread.
  ThreadHandlesClosure handles_closure(f);
  threads_do(&handles_closure);
}

// Get count Java threads that are waiting to enter the specified monitor.
GrowableArray<JavaThread*>* Threads::get_pending_threads(ThreadsList * t_list,
                                                         int count,
                                                         address monitor) {
  GrowableArray<JavaThread*>* result = new GrowableArray<JavaThread*>(count);

  int i = 0;
  for (JavaThread* p : *t_list) {
    if (!p->can_call_java()) continue;

    // The first stage of async deflation does not affect any field
    // used by this comparison so the ObjectMonitor* is usable here.
    address pending = (address)p->current_pending_monitor();
    if (pending == monitor) {             // found a match
      if (i < count) result->append(p);   // save the first count matches
      i++;
    }
  }

  return result;
}


JavaThread *Threads::owning_thread_from_monitor_owner(ThreadsList * t_list,
                                                      address owner) {
  // NULL owner means not locked so we can skip the search
  if (owner == NULL) return NULL;

  for (JavaThread* p : *t_list) {
    // first, see if owner is the address of a Java thread
    if (owner == (address)p) return p;
  }

  // Cannot assert on lack of success here since this function may be
  // used by code that is trying to report useful problem information
  // like deadlock detection.
  if (UseHeavyMonitors) return NULL;

  // If we didn't find a matching Java thread and we didn't force use of
  // heavyweight monitors, then the owner is the stack address of the
  // Lock Word in the owning Java thread's stack.
  //
  JavaThread* the_owner = NULL;
  for (JavaThread* q : *t_list) {
    if (q->is_lock_owned(owner)) {
      the_owner = q;
      break;
    }
  }

  // cannot assert on lack of success here; see above comment
  return the_owner;
}

class PrintOnClosure : public ThreadClosure {
private:
  outputStream* _st;

public:
  PrintOnClosure(outputStream* st) :
      _st(st) {}

  virtual void do_thread(Thread* thread) {
    if (thread != NULL) {
      thread->print_on(_st);
      _st->cr();
    }
  }
};

// Threads::print_on() is called at safepoint by VM_PrintThreads operation.
void Threads::print_on(outputStream* st, bool print_stacks,
                       bool internal_format, bool print_concurrent_locks,
                       bool print_extended_info) {
  char buf[32];
  st->print_raw_cr(os::local_time_string(buf, sizeof(buf)));

  st->print_cr("Full thread dump %s (%s %s):",
               VM_Version::vm_name(),
               VM_Version::vm_release(),
               VM_Version::vm_info_string());
  st->cr();

#if INCLUDE_SERVICES
  // Dump concurrent locks
  ConcurrentLocksDump concurrent_locks;
  if (print_concurrent_locks) {
    concurrent_locks.dump_at_safepoint();
  }
#endif // INCLUDE_SERVICES

  ThreadsSMRSupport::print_info_on(st);
  st->cr();

  ALL_JAVA_THREADS(p) {
    ResourceMark rm;
    p->print_on(st, print_extended_info);
    if (print_stacks) {
      if (internal_format) {
        p->trace_stack();
      } else {
        p->print_stack_on(st);
      }
    }
    st->cr();
#if INCLUDE_SERVICES
    if (print_concurrent_locks) {
      concurrent_locks.print_locks_on(p, st);
    }
#endif // INCLUDE_SERVICES
  }

  PrintOnClosure cl(st);
  cl.do_thread(VMThread::vm_thread());
  Universe::heap()->gc_threads_do(&cl);
  if (StringDedup::is_enabled()) {
    StringDedup::threads_do(&cl);
  }
  cl.do_thread(WatcherThread::watcher_thread());
  cl.do_thread(AsyncLogWriter::instance());

  st->flush();
}

void Threads::print_on_error(Thread* this_thread, outputStream* st, Thread* current, char* buf,
                             int buflen, bool* found_current) {
  if (this_thread != NULL) {
    bool is_current = (current == this_thread);
    *found_current = *found_current || is_current;
    st->print("%s", is_current ? "=>" : "  ");

    st->print(PTR_FORMAT, p2i(this_thread));
    st->print(" ");
    this_thread->print_on_error(st, buf, buflen);
    st->cr();
  }
}

class PrintOnErrorClosure : public ThreadClosure {
  outputStream* _st;
  Thread* _current;
  char* _buf;
  int _buflen;
  bool* _found_current;
 public:
  PrintOnErrorClosure(outputStream* st, Thread* current, char* buf,
                      int buflen, bool* found_current) :
   _st(st), _current(current), _buf(buf), _buflen(buflen), _found_current(found_current) {}

  virtual void do_thread(Thread* thread) {
    Threads::print_on_error(thread, _st, _current, _buf, _buflen, _found_current);
  }
};

// Threads::print_on_error() is called by fatal error handler. It's possible
// that VM is not at safepoint and/or current thread is inside signal handler.
// Don't print stack trace, as the stack may not be walkable. Don't allocate
// memory (even in resource area), it might deadlock the error handler.
void Threads::print_on_error(outputStream* st, Thread* current, char* buf,
                             int buflen) {
  ThreadsSMRSupport::print_info_on(st);
  st->cr();

  bool found_current = false;
  st->print_cr("Java Threads: ( => current thread )");
  ALL_JAVA_THREADS(thread) {
    print_on_error(thread, st, current, buf, buflen, &found_current);
  }
  st->cr();

  st->print_cr("Other Threads:");
  print_on_error(VMThread::vm_thread(), st, current, buf, buflen, &found_current);
  print_on_error(WatcherThread::watcher_thread(), st, current, buf, buflen, &found_current);
  print_on_error(AsyncLogWriter::instance(), st, current, buf, buflen, &found_current);

  if (Universe::heap() != NULL) {
    PrintOnErrorClosure print_closure(st, current, buf, buflen, &found_current);
    Universe::heap()->gc_threads_do(&print_closure);
  }

  if (StringDedup::is_enabled()) {
    PrintOnErrorClosure print_closure(st, current, buf, buflen, &found_current);
    StringDedup::threads_do(&print_closure);
  }

  if (!found_current) {
    st->cr();
    st->print("=>" PTR_FORMAT " (exited) ", p2i(current));
    current->print_on_error(st, buf, buflen);
    st->cr();
  }
  st->cr();

  st->print_cr("Threads with active compile tasks:");
  print_threads_compiling(st, buf, buflen);
}

void Threads::print_threads_compiling(outputStream* st, char* buf, int buflen, bool short_form) {
  ALL_JAVA_THREADS(thread) {
    if (thread->is_Compiler_thread()) {
      CompilerThread* ct = (CompilerThread*) thread;

      // Keep task in local variable for NULL check.
      // ct->_task might be set to NULL by concurring compiler thread
      // because it completed the compilation. The task is never freed,
      // though, just returned to a free list.
      CompileTask* task = ct->task();
      if (task != NULL) {
        thread->print_name_on_error(st, buf, buflen);
        st->print("  ");
        task->print(st, NULL, short_form, true);
      }
    }
  }
}

void Threads::verify() {
  ALL_JAVA_THREADS(p) {
    p->verify();
  }
  VMThread* thread = VMThread::vm_thread();
  if (thread != NULL) thread->verify();
}
