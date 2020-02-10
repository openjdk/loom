/*
 * Copyright (c) 1997, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

#ifndef _JAVASOFT_JVM_H_
#define _JAVASOFT_JVM_H_

#include <sys/stat.h>

#include "jni.h"
#include "jvm_md.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * This file contains additional functions exported from the VM.
 * These functions are complementary to the standard JNI support.
 * There are three parts to this file:
 *
 * First, this file contains the VM-related functions needed by native
 * libraries in the standard Java API. For example, the java.lang.Object
 * class needs VM-level functions that wait for and notify monitors.
 *
 * Second, this file contains the functions and constant definitions
 * needed by the byte code verifier and class file format checker.
 * These functions allow the verifier and format checker to be written
 * in a VM-independent way.
 *
 * Third, this file contains various I/O and network operations needed
 * by the standard Java I/O and network APIs.
 */

/*
 * Bump the version number when either of the following happens:
 *
 * 1. There is a change in JVM_* functions.
 *
 * 2. There is a change in the contract between VM and Java classes.
 *    For example, if the VM relies on a new private field in Thread
 *    class.
 */

#define JVM_INTERFACE_VERSION 6

JNIEXPORT jint JNICALL
JVM_GetInterfaceVersion(void);

/*************************************************************************
 PART 1: Functions for Native Libraries
 ************************************************************************/
/*
 * java.lang.Object
 */
JNIEXPORT jint JNICALL
JVM_IHashCode(JNIEnv *env, jobject obj);

JNIEXPORT void JNICALL
JVM_MonitorWait(JNIEnv *env, jobject obj, jlong ms);

JNIEXPORT void JNICALL
JVM_MonitorNotify(JNIEnv *env, jobject obj);

JNIEXPORT void JNICALL
JVM_MonitorNotifyAll(JNIEnv *env, jobject obj);

JNIEXPORT jobject JNICALL
JVM_Clone(JNIEnv *env, jobject obj);

/*
 * java.lang.String
 */
JNIEXPORT jstring JNICALL
JVM_InternString(JNIEnv *env, jstring str);

/*
 * java.lang.System
 */
JNIEXPORT jlong JNICALL
JVM_CurrentTimeMillis(JNIEnv *env, jclass ignored);

JNIEXPORT jlong JNICALL
JVM_NanoTime(JNIEnv *env, jclass ignored);

JNIEXPORT jlong JNICALL
JVM_GetNanoTimeAdjustment(JNIEnv *env, jclass ignored, jlong offset_secs);

JNIEXPORT void JNICALL
JVM_ArrayCopy(JNIEnv *env, jclass ignored, jobject src, jint src_pos,
              jobject dst, jint dst_pos, jint length);

/*
 * Return an array of all properties as alternating name and value pairs.
 */
JNIEXPORT jobjectArray JNICALL
JVM_GetProperties(JNIEnv *env);

/*
 * java.lang.Runtime
 */
JNIEXPORT void JNICALL
JVM_BeforeHalt();

JNIEXPORT void JNICALL
JVM_Halt(jint code);

JNIEXPORT void JNICALL
JVM_GC(void);

/* Returns the number of real-time milliseconds that have elapsed since the
 * least-recently-inspected heap object was last inspected by the garbage
 * collector.
 *
 * For simple stop-the-world collectors this value is just the time
 * since the most recent collection.  For generational collectors it is the
 * time since the oldest generation was most recently collected.  Other
 * collectors are free to return a pessimistic estimate of the elapsed time, or
 * simply the time since the last full collection was performed.
 *
 * Note that in the presence of reference objects, a given object that is no
 * longer strongly reachable may have to be inspected multiple times before it
 * can be reclaimed.
 */
JNIEXPORT jlong JNICALL
JVM_MaxObjectInspectionAge(void);

JNIEXPORT jlong JNICALL
JVM_TotalMemory(void);

JNIEXPORT jlong JNICALL
JVM_FreeMemory(void);

JNIEXPORT jlong JNICALL
JVM_MaxMemory(void);

JNIEXPORT jint JNICALL
JVM_ActiveProcessorCount(void);

JNIEXPORT void * JNICALL
JVM_LoadLibrary(const char *name);

JNIEXPORT void JNICALL
JVM_UnloadLibrary(void * handle);

JNIEXPORT void * JNICALL
JVM_FindLibraryEntry(void *handle, const char *name);

JNIEXPORT jboolean JNICALL
JVM_IsSupportedJNIVersion(jint version);

JNIEXPORT jobjectArray JNICALL
JVM_GetVmArguments(JNIEnv *env);

JNIEXPORT void JNICALL
JVM_InitializeFromArchive(JNIEnv* env, jclass cls);

/*
 * java.lang.Throwable
 */
JNIEXPORT void JNICALL
JVM_FillInStackTrace(JNIEnv *env, jobject throwable, jobject contScope);

/*
 * java.lang.StackTraceElement
 */
JNIEXPORT void JNICALL
JVM_InitStackTraceElementArray(JNIEnv *env, jobjectArray elements, jobject throwable);

JNIEXPORT void JNICALL
JVM_InitStackTraceElement(JNIEnv* env, jobject element, jobject stackFrameInfo);

/*
 * java.lang.NullPointerException
 */

JNIEXPORT jstring JNICALL
JVM_GetExtendedNPEMessage(JNIEnv *env, jthrowable throwable);

/*
 * java.lang.StackWalker
 */
enum {
  JVM_STACKWALK_FILL_CLASS_REFS_ONLY       = 0x2,
  JVM_STACKWALK_GET_CALLER_CLASS           = 0x04,
  JVM_STACKWALK_SHOW_HIDDEN_FRAMES         = 0x20,
  JVM_STACKWALK_FILL_LIVE_STACK_FRAMES     = 0x100
};

JNIEXPORT jobject JNICALL
JVM_CallStackWalk(JNIEnv *env, jobject stackStream, jlong mode,
                  jint skip_frames, jobject contScope, jobject cont,
                  jint frame_count, jint start_index, jobjectArray frames);

JNIEXPORT jobject JNICALL
JVM_ScopedCache(JNIEnv *env, jclass threadClass);

JNIEXPORT void JNICALL
JVM_SetScopedCache(JNIEnv *env, jclass threadClass, jobject theCache);

JNIEXPORT jint JNICALL
JVM_MoreStackWalk(JNIEnv *env, jobject stackStream, jlong mode, jlong anchor, 
                  jint frame_count, jint start_index, 
                  jobjectArray frames);

JNIEXPORT void JNICALL
JVM_SetStackWalkContinuation(JNIEnv *env, jobject stackStream, jlong anchor, jobjectArray frames, jobject cont);

/*
 * java.lang.Thread
 */
JNIEXPORT void JNICALL
JVM_StartThread(JNIEnv *env, jobject thread);

JNIEXPORT void JNICALL
JVM_StopThread(JNIEnv *env, jobject thread, jobject exception);

JNIEXPORT jboolean JNICALL
JVM_IsThreadAlive(JNIEnv *env, jobject thread);

JNIEXPORT void JNICALL
JVM_SuspendThread(JNIEnv *env, jobject thread);

JNIEXPORT void JNICALL
JVM_ResumeThread(JNIEnv *env, jobject thread);

JNIEXPORT void JNICALL
JVM_SetThreadPriority(JNIEnv *env, jobject thread, jint prio);

JNIEXPORT void JNICALL
JVM_Yield(JNIEnv *env, jclass threadClass);

JNIEXPORT void JNICALL
JVM_Sleep(JNIEnv *env, jclass threadClass, jlong millis);

JNIEXPORT jobject JNICALL
JVM_CurrentThread(JNIEnv *env, jclass threadClass);

JNIEXPORT void JNICALL
JVM_Interrupt(JNIEnv *env, jobject thread);

JNIEXPORT jboolean JNICALL
JVM_HoldsLock(JNIEnv *env, jclass threadClass, jobject obj);

JNIEXPORT void JNICALL
JVM_DumpAllStacks(JNIEnv *env, jclass unused);

JNIEXPORT jobjectArray JNICALL
JVM_GetAllThreads(JNIEnv *env, jclass dummy);

JNIEXPORT void JNICALL
JVM_SetNativeThreadName(JNIEnv *env, jobject jthread, jstring name);

/* getStackTrace() and getAllStackTraces() method */
JNIEXPORT jobjectArray JNICALL
JVM_DumpThreads(JNIEnv *env, jclass threadClass, jobjectArray threads);

/*
 * java.lang.Continuation
 */
JNIEXPORT void JNICALL
JVM_RegisterContinuationMethods(JNIEnv *env, jclass cls);

/*
 * java.lang.SecurityManager
 */
JNIEXPORT jobjectArray JNICALL
JVM_GetClassContext(JNIEnv *env);

/*
 * java.lang.Package
 */
JNIEXPORT jstring JNICALL
JVM_GetSystemPackage(JNIEnv *env, jstring name);

JNIEXPORT jobjectArray JNICALL
JVM_GetSystemPackages(JNIEnv *env);

/*
 * java.lang.ref.Reference
 */
JNIEXPORT jobject JNICALL
JVM_GetAndClearReferencePendingList(JNIEnv *env);

JNIEXPORT jboolean JNICALL
JVM_HasReferencePendingList(JNIEnv *env);

JNIEXPORT void JNICALL
JVM_WaitForReferencePendingList(JNIEnv *env);

/*
 * java.io.ObjectInputStream
 */
JNIEXPORT jobject JNICALL
JVM_LatestUserDefinedLoader(JNIEnv *env);

/*
 * java.lang.reflect.Array
 */
JNIEXPORT jint JNICALL
JVM_GetArrayLength(JNIEnv *env, jobject arr);

JNIEXPORT jobject JNICALL
JVM_GetArrayElement(JNIEnv *env, jobject arr, jint index);

JNIEXPORT jvalue JNICALL
JVM_GetPrimitiveArrayElement(JNIEnv *env, jobject arr, jint index, jint wCode);

JNIEXPORT void JNICALL
JVM_SetArrayElement(JNIEnv *env, jobject arr, jint index, jobject val);

JNIEXPORT void JNICALL
JVM_SetPrimitiveArrayElement(JNIEnv *env, jobject arr, jint index, jvalue v,
                             unsigned char vCode);

JNIEXPORT jobject JNICALL
JVM_NewArray(JNIEnv *env, jclass eltClass, jint length);

JNIEXPORT jobject JNICALL
JVM_NewMultiArray(JNIEnv *env, jclass eltClass, jintArray dim);


/*
 * Returns the immediate caller class of the native method invoking
 * JVM_GetCallerClass.  The Method.invoke and other frames due to
 * reflection machinery are skipped.
 *
 * The caller is expected to be marked with
 * jdk.internal.reflect.CallerSensitive. The JVM will throw an
 * error if it is not marked properly.
 */
JNIEXPORT jclass JNICALL
JVM_GetCallerClass(JNIEnv *env);


/*
 * Find primitive classes
 * utf: class name
 */
JNIEXPORT jclass JNICALL
JVM_FindPrimitiveClass(JNIEnv *env, const char *utf);


/*
 * Find a class from a boot class loader. Returns NULL if class not found.
 */
JNIEXPORT jclass JNICALL
JVM_FindClassFromBootLoader(JNIEnv *env, const char *name);

/*
 * Find a class from a given class loader.  Throws ClassNotFoundException.
 *  name:   name of class
 *  init:   whether initialization is done
 *  loader: class loader to look up the class. This may not be the same as the caller's
 *          class loader.
 *  caller: initiating class. The initiating class may be null when a security
 *          manager is not installed.
 */
JNIEXPORT jclass JNICALL
JVM_FindClassFromCaller(JNIEnv *env, const char *name, jboolean init,
                        jobject loader, jclass caller);

/*
 * Find a class from a given class.
 */
JNIEXPORT jclass JNICALL
JVM_FindClassFromClass(JNIEnv *env, const char *name, jboolean init,
                             jclass from);

/* Find a loaded class cached by the VM */
JNIEXPORT jclass JNICALL
JVM_FindLoadedClass(JNIEnv *env, jobject loader, jstring name);

/* Define a class */
JNIEXPORT jclass JNICALL
JVM_DefineClass(JNIEnv *env, const char *name, jobject loader, const jbyte *buf,
                jsize len, jobject pd);

/* Define a class with a source (added in JDK1.5) */
JNIEXPORT jclass JNICALL
JVM_DefineClassWithSource(JNIEnv *env, const char *name, jobject loader,
                          const jbyte *buf, jsize len, jobject pd,
                          const char *source);

/*
 * Module support funcions
 */

/*
 * Define a module with the specified packages and bind the module to the
 * given class loader.
 *  module:       module to define
 *  is_open:      specifies if module is open (currently ignored)
 *  version:      the module version
 *  location:     the module location
 *  packages:     list of packages in the module
 *  num_packages: number of packages in the module
 */
JNIEXPORT void JNICALL
JVM_DefineModule(JNIEnv *env, jobject module, jboolean is_open, jstring version,
                 jstring location, const char* const* packages, jsize num_packages);

/*
 * Set the boot loader's unnamed module.
 *  module: boot loader's unnamed module
 */
JNIEXPORT void JNICALL
JVM_SetBootLoaderUnnamedModule(JNIEnv *env, jobject module);

/*
 * Do a qualified export of a package.
 *  from_module: module containing the package to export
 *  package:     name of the package to export
 *  to_module:   module to export the package to
 */
JNIEXPORT void JNICALL
JVM_AddModuleExports(JNIEnv *env, jobject from_module, const char* package, jobject to_module);

/*
 * Do an export of a package to all unnamed modules.
 *  from_module: module containing the package to export
 *  package:     name of the package to export to all unnamed modules
 */
JNIEXPORT void JNICALL
JVM_AddModuleExportsToAllUnnamed(JNIEnv *env, jobject from_module, const char* package);

/*
 * Do an unqualified export of a package.
 *  from_module: module containing the package to export
 *  package:     name of the package to export
 */
JNIEXPORT void JNICALL
JVM_AddModuleExportsToAll(JNIEnv *env, jobject from_module, const char* package);

/*
 * Add a module to the list of modules that a given module can read.
 *  from_module:   module requesting read access
 *  source_module: module that from_module wants to read
 */
JNIEXPORT void JNICALL
JVM_AddReadsModule(JNIEnv *env, jobject from_module, jobject source_module);

/*
 * Reflection support functions
 */

JNIEXPORT jstring JNICALL
JVM_InitClassName(JNIEnv *env, jclass cls);

JNIEXPORT jobjectArray JNICALL
JVM_GetClassInterfaces(JNIEnv *env, jclass cls);

JNIEXPORT jboolean JNICALL
JVM_IsInterface(JNIEnv *env, jclass cls);

JNIEXPORT jobjectArray JNICALL
JVM_GetClassSigners(JNIEnv *env, jclass cls);

JNIEXPORT void JNICALL
JVM_SetClassSigners(JNIEnv *env, jclass cls, jobjectArray signers);

JNIEXPORT jobject JNICALL
JVM_GetProtectionDomain(JNIEnv *env, jclass cls);

JNIEXPORT jboolean JNICALL
JVM_IsArrayClass(JNIEnv *env, jclass cls);

JNIEXPORT jboolean JNICALL
JVM_IsPrimitiveClass(JNIEnv *env, jclass cls);

JNIEXPORT jint JNICALL
JVM_GetClassModifiers(JNIEnv *env, jclass cls);

JNIEXPORT jobjectArray JNICALL
JVM_GetDeclaredClasses(JNIEnv *env, jclass ofClass);

JNIEXPORT jclass JNICALL
JVM_GetDeclaringClass(JNIEnv *env, jclass ofClass);

JNIEXPORT jstring JNICALL
JVM_GetSimpleBinaryName(JNIEnv *env, jclass ofClass);

/* Generics support (JDK 1.5) */
JNIEXPORT jstring JNICALL
JVM_GetClassSignature(JNIEnv *env, jclass cls);

/* Annotations support (JDK 1.5) */
JNIEXPORT jbyteArray JNICALL
JVM_GetClassAnnotations(JNIEnv *env, jclass cls);

/* Type use annotations support (JDK 1.8) */

JNIEXPORT jbyteArray JNICALL
JVM_GetClassTypeAnnotations(JNIEnv *env, jclass cls);

JNIEXPORT jbyteArray JNICALL
JVM_GetFieldTypeAnnotations(JNIEnv *env, jobject field);

JNIEXPORT jbyteArray JNICALL
JVM_GetMethodTypeAnnotations(JNIEnv *env, jobject method);

/*
 * New (JDK 1.4) reflection implementation
 */

JNIEXPORT jobjectArray JNICALL
JVM_GetClassDeclaredMethods(JNIEnv *env, jclass ofClass, jboolean publicOnly);

JNIEXPORT jobjectArray JNICALL
JVM_GetClassDeclaredFields(JNIEnv *env, jclass ofClass, jboolean publicOnly);

JNIEXPORT jobjectArray JNICALL
JVM_GetClassDeclaredConstructors(JNIEnv *env, jclass ofClass, jboolean publicOnly);


/* Differs from JVM_GetClassModifiers in treatment of inner classes.
   This returns the access flags for the class as specified in the
   class file rather than searching the InnerClasses attribute (if
   present) to find the source-level access flags. Only the values of
   the low 13 bits (i.e., a mask of 0x1FFF) are guaranteed to be
   valid. */
JNIEXPORT jint JNICALL
JVM_GetClassAccessFlags(JNIEnv *env, jclass cls);

/* Nestmates - since JDK 11 */

JNIEXPORT jboolean JNICALL
JVM_AreNestMates(JNIEnv *env, jclass current, jclass member);

JNIEXPORT jclass JNICALL
JVM_GetNestHost(JNIEnv *env, jclass current);

JNIEXPORT jobjectArray JNICALL
JVM_GetNestMembers(JNIEnv *env, jclass current);

/* Records - since JDK 14 */

JNIEXPORT jboolean JNICALL
JVM_IsRecord(JNIEnv *env, jclass cls);

JNIEXPORT jobjectArray JNICALL
JVM_GetRecordComponents(JNIEnv *env, jclass ofClass);

/* The following two reflection routines are still needed due to startup time issues */
/*
 * java.lang.reflect.Method
 */
JNIEXPORT jobject JNICALL
JVM_InvokeMethod(JNIEnv *env, jobject method, jobject obj, jobjectArray args0);

/*
 * java.lang.reflect.Constructor
 */
JNIEXPORT jobject JNICALL
JVM_NewInstanceFromConstructor(JNIEnv *env, jobject c, jobjectArray args0);

/*
 * Constant pool access; currently used to implement reflective access to annotations (JDK 1.5)
 */

JNIEXPORT jobject JNICALL
JVM_GetClassConstantPool(JNIEnv *env, jclass cls);

JNIEXPORT jint JNICALL JVM_ConstantPoolGetSize
(JNIEnv *env, jobject unused, jobject jcpool);

JNIEXPORT jclass JNICALL JVM_ConstantPoolGetClassAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jclass JNICALL JVM_ConstantPoolGetClassAtIfLoaded
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jint JNICALL JVM_ConstantPoolGetClassRefIndexAt
(JNIEnv *env, jobject obj, jobject unused, jint index);

JNIEXPORT jobject JNICALL JVM_ConstantPoolGetMethodAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jobject JNICALL JVM_ConstantPoolGetMethodAtIfLoaded
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jobject JNICALL JVM_ConstantPoolGetFieldAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jobject JNICALL JVM_ConstantPoolGetFieldAtIfLoaded
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jobjectArray JNICALL JVM_ConstantPoolGetMemberRefInfoAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jint JNICALL JVM_ConstantPoolGetNameAndTypeRefIndexAt
(JNIEnv *env, jobject obj, jobject unused, jint index);

JNIEXPORT jobjectArray JNICALL JVM_ConstantPoolGetNameAndTypeRefInfoAt
(JNIEnv *env, jobject obj, jobject unused, jint index);

JNIEXPORT jint JNICALL JVM_ConstantPoolGetIntAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jlong JNICALL JVM_ConstantPoolGetLongAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jfloat JNICALL JVM_ConstantPoolGetFloatAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jdouble JNICALL JVM_ConstantPoolGetDoubleAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jstring JNICALL JVM_ConstantPoolGetStringAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jstring JNICALL JVM_ConstantPoolGetUTF8At
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

JNIEXPORT jbyte JNICALL JVM_ConstantPoolGetTagAt
(JNIEnv *env, jobject unused, jobject jcpool, jint index);

/*
 * Parameter reflection
 */

JNIEXPORT jobjectArray JNICALL
JVM_GetMethodParameters(JNIEnv *env, jobject method);

/*
 * java.security.*
 */

JNIEXPORT jobject JNICALL
JVM_GetInheritedAccessControlContext(JNIEnv *env, jclass cls);

/*
 * Ensure that code doing a stackwalk and using javaVFrame::locals() to
 * get the value will see a materialized value and not a scalar-replaced
 * null value.
 */
#define JVM_EnsureMaterializedForStackWalk(env, value) \
    do {} while(0) // Nothing to do.  The fact that the value escaped
                   // through a native method is enough.

JNIEXPORT jobject JNICALL
JVM_GetStackAccessControlContext(JNIEnv *env, jclass cls);

/*
 * Signal support, used to implement the shutdown sequence.  Every VM must
 * support JVM_SIGINT and JVM_SIGTERM, raising the former for user interrupts
 * (^C) and the latter for external termination (kill, system shutdown, etc.).
 * Other platform-dependent signal values may also be supported.
 */

JNIEXPORT void * JNICALL
JVM_RegisterSignal(jint sig, void *handler);

JNIEXPORT jboolean JNICALL
JVM_RaiseSignal(jint sig);

JNIEXPORT jint JNICALL
JVM_FindSignal(const char *name);

/*
 * Retrieve the assertion directives for the specified class.
 */
JNIEXPORT jboolean JNICALL
JVM_DesiredAssertionStatus(JNIEnv *env, jclass unused, jclass cls);

/*
 * Retrieve the assertion directives from the VM.
 */
JNIEXPORT jobject JNICALL
JVM_AssertionStatusDirectives(JNIEnv *env, jclass unused);

/*
 * java.util.concurrent.atomic.AtomicLong
 */
JNIEXPORT jboolean JNICALL
JVM_SupportsCX8(void);

/*
 * com.sun.dtrace.jsdt support
 */

#define JVM_TRACING_DTRACE_VERSION 1

/*
 * Structure to pass one probe description to JVM
 */
typedef struct {
    jmethodID method;
    jstring   function;
    jstring   name;
    void*            reserved[4];     // for future use
} JVM_DTraceProbe;

/**
 * Encapsulates the stability ratings for a DTrace provider field
 */
typedef struct {
    jint nameStability;
    jint dataStability;
    jint dependencyClass;
} JVM_DTraceInterfaceAttributes;

/*
 * Structure to pass one provider description to JVM
 */
typedef struct {
    jstring                       name;
    JVM_DTraceProbe*              probes;
    jint                          probe_count;
    JVM_DTraceInterfaceAttributes providerAttributes;
    JVM_DTraceInterfaceAttributes moduleAttributes;
    JVM_DTraceInterfaceAttributes functionAttributes;
    JVM_DTraceInterfaceAttributes nameAttributes;
    JVM_DTraceInterfaceAttributes argsAttributes;
    void*                         reserved[4]; // for future use
} JVM_DTraceProvider;

/*
 * Get the version number the JVM was built with
 */
JNIEXPORT jint JNICALL
JVM_DTraceGetVersion(JNIEnv* env);

/*
 * Register new probe with given signature, return global handle
 *
 * The version passed in is the version that the library code was
 * built with.
 */
JNIEXPORT jlong JNICALL
JVM_DTraceActivate(JNIEnv* env, jint version, jstring module_name,
  jint providers_count, JVM_DTraceProvider* providers);

/*
 * Check JSDT probe
 */
JNIEXPORT jboolean JNICALL
JVM_DTraceIsProbeEnabled(JNIEnv* env, jmethodID method);

/*
 * Destroy custom DOF
 */
JNIEXPORT void JNICALL
JVM_DTraceDispose(JNIEnv* env, jlong activation_handle);

/*
 * Check to see if DTrace is supported by OS
 */
JNIEXPORT jboolean JNICALL
JVM_DTraceIsSupported(JNIEnv* env);

/*************************************************************************
 PART 2: Support for the Verifier and Class File Format Checker
 ************************************************************************/
/*
 * Return the class name in UTF format. The result is valid
 * until JVM_ReleaseUTf is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetClassNameUTF(JNIEnv *env, jclass cb);

/*
 * Returns the constant pool types in the buffer provided by "types."
 */
JNIEXPORT void JNICALL
JVM_GetClassCPTypes(JNIEnv *env, jclass cb, unsigned char *types);

/*
 * Returns the number of Constant Pool entries.
 */
JNIEXPORT jint JNICALL
JVM_GetClassCPEntriesCount(JNIEnv *env, jclass cb);

/*
 * Returns the number of *declared* fields or methods.
 */
JNIEXPORT jint JNICALL
JVM_GetClassFieldsCount(JNIEnv *env, jclass cb);

JNIEXPORT jint JNICALL
JVM_GetClassMethodsCount(JNIEnv *env, jclass cb);

/*
 * Returns the CP indexes of exceptions raised by a given method.
 * Places the result in the given buffer.
 *
 * The method is identified by method_index.
 */
JNIEXPORT void JNICALL
JVM_GetMethodIxExceptionIndexes(JNIEnv *env, jclass cb, jint method_index,
                                unsigned short *exceptions);
/*
 * Returns the number of exceptions raised by a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxExceptionsCount(JNIEnv *env, jclass cb, jint method_index);

/*
 * Returns the byte code sequence of a given method.
 * Places the result in the given buffer.
 *
 * The method is identified by method_index.
 */
JNIEXPORT void JNICALL
JVM_GetMethodIxByteCode(JNIEnv *env, jclass cb, jint method_index,
                        unsigned char *code);

/*
 * Returns the length of the byte code sequence of a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxByteCodeLength(JNIEnv *env, jclass cb, jint method_index);

/*
 * A structure used to a capture exception table entry in a Java method.
 */
typedef struct {
    jint start_pc;
    jint end_pc;
    jint handler_pc;
    jint catchType;
} JVM_ExceptionTableEntryType;

/*
 * Returns the exception table entry at entry_index of a given method.
 * Places the result in the given buffer.
 *
 * The method is identified by method_index.
 */
JNIEXPORT void JNICALL
JVM_GetMethodIxExceptionTableEntry(JNIEnv *env, jclass cb, jint method_index,
                                   jint entry_index,
                                   JVM_ExceptionTableEntryType *entry);

/*
 * Returns the length of the exception table of a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxExceptionTableLength(JNIEnv *env, jclass cb, int index);

/*
 * Returns the modifiers of a given field.
 * The field is identified by field_index.
 */
JNIEXPORT jint JNICALL
JVM_GetFieldIxModifiers(JNIEnv *env, jclass cb, int index);

/*
 * Returns the modifiers of a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxModifiers(JNIEnv *env, jclass cb, int index);

/*
 * Returns the number of local variables of a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxLocalsCount(JNIEnv *env, jclass cb, int index);

/*
 * Returns the number of arguments (including this pointer) of a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxArgsSize(JNIEnv *env, jclass cb, int index);

/*
 * Returns the maximum amount of stack (in words) used by a given method.
 * The method is identified by method_index.
 */
JNIEXPORT jint JNICALL
JVM_GetMethodIxMaxStack(JNIEnv *env, jclass cb, int index);

/*
 * Is a given method a constructor.
 * The method is identified by method_index.
 */
JNIEXPORT jboolean JNICALL
JVM_IsConstructorIx(JNIEnv *env, jclass cb, int index);

/*
 * Is the given method generated by the VM.
 * The method is identified by method_index.
 */
JNIEXPORT jboolean JNICALL
JVM_IsVMGeneratedMethodIx(JNIEnv *env, jclass cb, int index);

/*
 * Returns the name of a given method in UTF format.
 * The result remains valid until JVM_ReleaseUTF is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetMethodIxNameUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the signature of a given method in UTF format.
 * The result remains valid until JVM_ReleaseUTF is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetMethodIxSignatureUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the name of the field referred to at a given constant pool
 * index.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPFieldNameUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the name of the method referred to at a given constant pool
 * index.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPMethodNameUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the signature of the method referred to at a given constant pool
 * index.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPMethodSignatureUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the signature of the field referred to at a given constant pool
 * index.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPFieldSignatureUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the class name referred to at a given constant pool index.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPClassNameUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the class name referred to at a given constant pool index.
 *
 * The constant pool entry must refer to a CONSTANT_Fieldref.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPFieldClassNameUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the class name referred to at a given constant pool index.
 *
 * The constant pool entry must refer to CONSTANT_Methodref or
 * CONSTANT_InterfaceMethodref.
 *
 * The result is in UTF format and remains valid until JVM_ReleaseUTF
 * is called.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 */
JNIEXPORT const char * JNICALL
JVM_GetCPMethodClassNameUTF(JNIEnv *env, jclass cb, jint index);

/*
 * Returns the modifiers of a field in calledClass. The field is
 * referred to in class cb at constant pool entry index.
 *
 * The caller must treat the string as a constant and not modify it
 * in any way.
 *
 * Returns -1 if the field does not exist in calledClass.
 */
JNIEXPORT jint JNICALL
JVM_GetCPFieldModifiers(JNIEnv *env, jclass cb, int index, jclass calledClass);

/*
 * Returns the modifiers of a method in calledClass. The method is
 * referred to in class cb at constant pool entry index.
 *
 * Returns -1 if the method does not exist in calledClass.
 */
JNIEXPORT jint JNICALL
JVM_GetCPMethodModifiers(JNIEnv *env, jclass cb, int index, jclass calledClass);

/*
 * Releases the UTF string obtained from the VM.
 */
JNIEXPORT void JNICALL
JVM_ReleaseUTF(const char *utf);

/*
 * Compare if two classes are in the same package.
 */
JNIEXPORT jboolean JNICALL
JVM_IsSameClassPackage(JNIEnv *env, jclass class1, jclass class2);

/* Get classfile constants */
#include "classfile_constants.h"

/*
 * Support for a VM-independent class format checker.
 */
typedef struct {
    unsigned long code;    /* byte code */
    unsigned long excs;    /* exceptions */
    unsigned long etab;    /* catch table */
    unsigned long lnum;    /* line number */
    unsigned long lvar;    /* local vars */
} method_size_info;

typedef struct {
    unsigned int constants;    /* constant pool */
    unsigned int fields;
    unsigned int methods;
    unsigned int interfaces;
    unsigned int fields2;      /* number of static 2-word fields */
    unsigned int innerclasses; /* # of records in InnerClasses attr */

    method_size_info clinit;   /* memory used in clinit */
    method_size_info main;     /* used everywhere else */
} class_size_info;

#define JVM_RECOGNIZED_CLASS_MODIFIERS (JVM_ACC_PUBLIC | \
                                        JVM_ACC_FINAL | \
                                        JVM_ACC_SUPER | \
                                        JVM_ACC_INTERFACE | \
                                        JVM_ACC_ABSTRACT | \
                                        JVM_ACC_ANNOTATION | \
                                        JVM_ACC_ENUM | \
                                        JVM_ACC_SYNTHETIC)

#define JVM_RECOGNIZED_FIELD_MODIFIERS (JVM_ACC_PUBLIC | \
                                        JVM_ACC_PRIVATE | \
                                        JVM_ACC_PROTECTED | \
                                        JVM_ACC_STATIC | \
                                        JVM_ACC_FINAL | \
                                        JVM_ACC_VOLATILE | \
                                        JVM_ACC_TRANSIENT | \
                                        JVM_ACC_ENUM | \
                                        JVM_ACC_SYNTHETIC)

#define JVM_RECOGNIZED_METHOD_MODIFIERS (JVM_ACC_PUBLIC | \
                                         JVM_ACC_PRIVATE | \
                                         JVM_ACC_PROTECTED | \
                                         JVM_ACC_STATIC | \
                                         JVM_ACC_FINAL | \
                                         JVM_ACC_SYNCHRONIZED | \
                                         JVM_ACC_BRIDGE | \
                                         JVM_ACC_VARARGS | \
                                         JVM_ACC_NATIVE | \
                                         JVM_ACC_ABSTRACT | \
                                         JVM_ACC_STRICT | \
                                         JVM_ACC_SYNTHETIC)


/*************************************************************************
 PART 3: I/O and Network Support
 ************************************************************************/

/*
 * Convert a pathname into native format.  This function does syntactic
 * cleanup, such as removing redundant separator characters.  It modifies
 * the given pathname string in place.
 */
JNIEXPORT char * JNICALL
JVM_NativePath(char *);

/*
 * The standard printing functions supported by the Java VM. (Should they
 * be renamed to JVM_* in the future?
 */

/* jio_snprintf() and jio_vsnprintf() behave like snprintf(3) and vsnprintf(3),
 *  respectively, with the following differences:
 * - The string written to str is always zero-terminated, also in case of
 *   truncation (count is too small to hold the result string), unless count
 *   is 0. In case of truncation count-1 characters are written and '\0'
 *   appendend.
 * - If count is too small to hold the whole string, -1 is returned across
 *   all platforms. */

JNIEXPORT int
jio_vsnprintf(char *str, size_t count, const char *fmt, va_list args);

JNIEXPORT int
jio_snprintf(char *str, size_t count, const char *fmt, ...);

JNIEXPORT int
jio_fprintf(FILE *, const char *fmt, ...);

JNIEXPORT int
jio_vfprintf(FILE *, const char *fmt, va_list args);


JNIEXPORT void * JNICALL
JVM_RawMonitorCreate(void);

JNIEXPORT void JNICALL
JVM_RawMonitorDestroy(void *mon);

JNIEXPORT jint JNICALL
JVM_RawMonitorEnter(void *mon);

JNIEXPORT void JNICALL
JVM_RawMonitorExit(void *mon);

/*
 * java.lang.management support
 */
JNIEXPORT void* JNICALL
JVM_GetManagement(jint version);

/*
 * com.sun.tools.attach.VirtualMachine support
 *
 * Initialize the agent properties with the properties maintained in the VM.
 */
JNIEXPORT jobject JNICALL
JVM_InitAgentProperties(JNIEnv *env, jobject agent_props);

JNIEXPORT jstring JNICALL
JVM_GetTemporaryDirectory(JNIEnv *env);

/* Generics reflection support.
 *
 * Returns information about the given class's EnclosingMethod
 * attribute, if present, or null if the class had no enclosing
 * method.
 *
 * If non-null, the returned array contains three elements. Element 0
 * is the java.lang.Class of which the enclosing method is a member,
 * and elements 1 and 2 are the java.lang.Strings for the enclosing
 * method's name and descriptor, respectively.
 */
JNIEXPORT jobjectArray JNICALL
JVM_GetEnclosingMethodInfo(JNIEnv* env, jclass ofClass);
    
/* Virtual thread support.
 */
JNIEXPORT void JNICALL
JVM_VirtualThreadStarted(JNIEnv* env, jclass vthread_class, jobject event_thread, jobject vthread);

JNIEXPORT void JNICALL
JVM_VirtualThreadTerminated(JNIEnv* env, jclass vthread_class, jobject event_hread, jobject vthread);

JNIEXPORT void JNICALL
JVM_VirtualThreadMount(JNIEnv* env, jclass vthread_class, jobject event_thread, jobject vthread);

JNIEXPORT void JNICALL
JVM_VirtualThreadUnmount(JNIEnv* env, jclass vthread_class, jobject event_hread, jobject vthread);

/*
 * This structure is used by the launcher to get the default thread
 * stack size from the VM using JNI_GetDefaultJavaVMInitArgs() with a
 * version of 1.1.  As it is not supported otherwise, it has been removed
 * from jni.h
 */
typedef struct JDK1_1InitArgs {
    jint version;

    char **properties;
    jint checkSource;
    jint nativeStackSize;
    jint javaStackSize;
    jint minHeapSize;
    jint maxHeapSize;
    jint verifyMode;
    char *classpath;

    jint (JNICALL *vfprintf)(FILE *fp, const char *format, va_list args);
    void (JNICALL *exit)(jint code);
    void (JNICALL *abort)(void);

    jint enableClassGC;
    jint enableVerboseGC;
    jint disableAsyncGC;
    jint verbose;
    jboolean debugging;
    jint debugPort;
} JDK1_1InitArgs;


#ifdef __cplusplus
} /* extern "C" */

#endif /* __cplusplus */

#endif /* !_JAVASOFT_JVM_H_ */
