/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019, 2021, Arm Limited. All rights reserved.
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
package jdk.internal.foreign.abi.aarch64;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryAddress;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import jdk.internal.foreign.abi.ABIDescriptor;
import jdk.internal.foreign.abi.Binding;
import jdk.internal.foreign.abi.CallingSequence;
import jdk.internal.foreign.abi.CallingSequenceBuilder;
import jdk.internal.foreign.abi.DowncallLinker;
import jdk.internal.foreign.abi.UpcallLinker;
import jdk.internal.foreign.abi.SharedUtils;
import jdk.internal.foreign.abi.VMStorage;
import jdk.internal.foreign.abi.aarch64.linux.LinuxAArch64CallArranger;
import jdk.internal.foreign.abi.aarch64.macos.MacOsAArch64CallArranger;
import jdk.internal.foreign.Utils;

import java.lang.foreign.MemorySession;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;

import static jdk.internal.foreign.PlatformLayouts.*;
import static jdk.internal.foreign.abi.aarch64.AArch64Architecture.*;

/**
 * For the AArch64 C ABI specifically, this class uses CallingSequenceBuilder
 * to translate a C FunctionDescriptor into a CallingSequence, which can then be turned into a MethodHandle.
 *
 * This includes taking care of synthetic arguments like pointers to return buffers for 'in-memory' returns.
 *
 * There are minor differences between the ABIs implemented on Linux, macOS, and Windows
 * which are handled in sub-classes. Clients should access these through the provided
 * public constants CallArranger.LINUX and CallArranger.MACOS.
 */
public abstract class CallArranger {
    private static final int STACK_SLOT_SIZE = 8;
    public static final int MAX_REGISTER_ARGUMENTS = 8;

    private static final VMStorage INDIRECT_RESULT = r8;

    // This is derived from the AAPCS64 spec, restricted to what's
    // possible when calling to/from C code.
    //
    // The indirect result register, r8, is used to return a large
    // struct by value. It's treated as an input here as the caller is
    // responsible for allocating storage and passing this into the
    // function.
    //
    // Although the AAPCS64 says r0-7 and v0-7 are all valid return
    // registers, it's not possible to generate a C function that uses
    // r2-7 and v4-7 so they are omitted here.
    private static final ABIDescriptor C = abiFor(
        new VMStorage[] { r0, r1, r2, r3, r4, r5, r6, r7, INDIRECT_RESULT},
        new VMStorage[] { v0, v1, v2, v3, v4, v5, v6, v7 },
        new VMStorage[] { r0, r1 },
        new VMStorage[] { v0, v1, v2, v3 },
        new VMStorage[] { r9, r10, r11, r12, r13, r14, r15 },
        new VMStorage[] { v16, v17, v18, v19, v20, v21, v22, v23, v25,
                          v26, v27, v28, v29, v30, v31 },
        16,  // Stack is always 16 byte aligned on AArch64
        0,   // No shadow space
        r9,  // target addr reg
        r10  // return buffer addr reg
    );

    // record
    public static class Bindings {
        public final CallingSequence callingSequence;
        public final boolean isInMemoryReturn;

        Bindings(CallingSequence callingSequence, boolean isInMemoryReturn) {
            this.callingSequence = callingSequence;
            this.isInMemoryReturn = isInMemoryReturn;
        }
    }

    public static final CallArranger LINUX = new LinuxAArch64CallArranger();
    public static final CallArranger MACOS = new MacOsAArch64CallArranger();

    /**
     * Are variadic arguments assigned to registers as in the standard calling
     * convention, or always passed on the stack?
     *
     * @return true if variadic arguments should be spilled to the stack.
      */
     protected abstract boolean varArgsOnStack();

    /**
     * {@return true if this ABI requires sub-slot (smaller than STACK_SLOT_SIZE) packing of arguments on the stack.}
     */
    protected abstract boolean requiresSubSlotStackPacking();

    protected CallArranger() {}

    public Bindings getBindings(MethodType mt, FunctionDescriptor cDesc, boolean forUpcall) {
        CallingSequenceBuilder csb = new CallingSequenceBuilder(C, forUpcall);

        BindingCalculator argCalc = forUpcall ? new BoxBindingCalculator(true) : new UnboxBindingCalculator(true);
        BindingCalculator retCalc = forUpcall ? new UnboxBindingCalculator(false) : new BoxBindingCalculator(false);

        boolean returnInMemory = isInMemoryReturn(cDesc.returnLayout());
        if (returnInMemory) {
            csb.addArgumentBindings(MemoryAddress.class, AArch64.C_POINTER,
                    argCalc.getIndirectBindings());
        } else if (cDesc.returnLayout().isPresent()) {
            Class<?> carrier = mt.returnType();
            MemoryLayout layout = cDesc.returnLayout().get();
            csb.setReturnBindings(carrier, layout, retCalc.getBindings(carrier, layout));
        }

        for (int i = 0; i < mt.parameterCount(); i++) {
            Class<?> carrier = mt.parameterType(i);
            MemoryLayout layout = cDesc.argumentLayouts().get(i);
            if (varArgsOnStack() && SharedUtils.isVarargsIndex(cDesc, i)) {
                argCalc.storageCalculator.adjustForVarArgs();
            }
            csb.addArgumentBindings(carrier, layout, argCalc.getBindings(carrier, layout));
        }

        return new Bindings(csb.build(), returnInMemory);
    }

    public MethodHandle arrangeDowncall(MethodType mt, FunctionDescriptor cDesc) {
        Bindings bindings = getBindings(mt, cDesc, false);

        MethodHandle handle = new DowncallLinker(C, bindings.callingSequence).getBoundMethodHandle();

        if (bindings.isInMemoryReturn) {
            handle = SharedUtils.adaptDowncallForIMR(handle, cDesc);
        }

        return handle;
    }

    public MemorySegment arrangeUpcall(MethodHandle target, MethodType mt, FunctionDescriptor cDesc, MemorySession session) {
        Bindings bindings = getBindings(mt, cDesc, true);

        if (bindings.isInMemoryReturn) {
            target = SharedUtils.adaptUpcallForIMR(target, true /* drop return, since we don't have bindings for it */);
        }

        return UpcallLinker.make(C, target, bindings.callingSequence, session);
    }

    private static boolean isInMemoryReturn(Optional<MemoryLayout> returnLayout) {
        return returnLayout
            .filter(GroupLayout.class::isInstance)
            .filter(g -> TypeClass.classifyLayout(g) == TypeClass.STRUCT_REFERENCE)
            .isPresent();
    }

    class StorageCalculator {
        private final boolean forArguments;
        private boolean forVarArgs = false;

        private final int[] nRegs = new int[] { 0, 0 };
        private long stackOffset = 0;

        public StorageCalculator(boolean forArguments) {
            this.forArguments = forArguments;
        }

        VMStorage stackAlloc(long size, long alignment) {
            assert forArguments : "no stack returns";
            // Implementation limit: each arg must take up at least an 8 byte stack slot (on the Java side)
            // There is currently no way to address stack offsets that are not multiples of 8 bytes
            // The VM can only address multiple-of-4-bytes offsets, which is also not good enough for some ABIs
            // see JDK-8283462 and related issues
            long stackSlotAlignment = Math.max(alignment, STACK_SLOT_SIZE);
            long alignedStackOffset = Utils.alignUp(stackOffset, stackSlotAlignment);
            // macos-aarch64 ABI potentially requires addressing stack offsets that are not multiples of 8 bytes
            // Reject such call types here, to prevent undefined behavior down the line
            // Reject if the above stack-slot-aligned offset does not match the offset the ABI really wants
            // Except for variadic arguments, which _are_ passed at 8-byte-aligned offsets
            if (requiresSubSlotStackPacking() && alignedStackOffset != Utils.alignUp(stackOffset, alignment)
                    && !forVarArgs) // varargs are given a pass on all aarch64 ABIs
                throw new UnsupportedOperationException("Call type not supported on this platform");

            stackOffset = alignedStackOffset;

            VMStorage storage =
                stackStorage((int)(stackOffset / STACK_SLOT_SIZE));
            stackOffset += size;
            return storage;
        }

        VMStorage stackAlloc(MemoryLayout layout) {
            return stackAlloc(layout.byteSize(), SharedUtils.alignment(layout, true));
        }

        VMStorage[] regAlloc(int type, int count) {
            if (nRegs[type] + count <= MAX_REGISTER_ARGUMENTS) {
                VMStorage[] source =
                    (forArguments ? C.inputStorage : C.outputStorage)[type];
                VMStorage[] result = new VMStorage[count];
                for (int i = 0; i < count; i++) {
                    result[i] = source[nRegs[type]++];
                }
                return result;
            } else {
                // Any further allocations for this register type must
                // be from the stack.
                nRegs[type] = MAX_REGISTER_ARGUMENTS;
                return null;
            }
        }

        VMStorage[] regAlloc(int type, MemoryLayout layout) {
            return regAlloc(type, (int)Utils.alignUp(layout.byteSize(), 8) / 8);
        }

        VMStorage nextStorage(int type, MemoryLayout layout) {
            VMStorage[] storage = regAlloc(type, 1);
            if (storage == null) {
                return stackAlloc(layout);
            }

            return storage[0];
        }

        void adjustForVarArgs() {
            // This system passes all variadic parameters on the stack. Ensure
            // no further arguments are allocated to registers.
            nRegs[StorageClasses.INTEGER] = MAX_REGISTER_ARGUMENTS;
            nRegs[StorageClasses.VECTOR] = MAX_REGISTER_ARGUMENTS;
            forVarArgs = true;
        }
    }

    abstract class BindingCalculator {
        protected final StorageCalculator storageCalculator;

        protected BindingCalculator(boolean forArguments) {
            this.storageCalculator = new StorageCalculator(forArguments);
        }

        protected void spillStructUnbox(Binding.Builder bindings, MemoryLayout layout) {
            // If a struct has been assigned register or HFA class but
            // there are not enough free registers to hold the entire
            // struct, it must be passed on the stack. I.e. not split
            // between registers and stack.

            long offset = 0;
            while (offset < layout.byteSize()) {
                long copy = Math.min(layout.byteSize() - offset, STACK_SLOT_SIZE);
                VMStorage storage =
                    storageCalculator.stackAlloc(copy, STACK_SLOT_SIZE);
                if (offset + STACK_SLOT_SIZE < layout.byteSize()) {
                    bindings.dup();
                }
                Class<?> type = SharedUtils.primitiveCarrierForSize(copy, false);
                bindings.bufferLoad(offset, type)
                        .vmStore(storage, type);
                offset += STACK_SLOT_SIZE;
            }
        }

        protected void spillStructBox(Binding.Builder bindings, MemoryLayout layout) {
            // If a struct has been assigned register or HFA class but
            // there are not enough free registers to hold the entire
            // struct, it must be passed on the stack. I.e. not split
            // between registers and stack.

            long offset = 0;
            while (offset < layout.byteSize()) {
                long copy = Math.min(layout.byteSize() - offset, STACK_SLOT_SIZE);
                VMStorage storage =
                    storageCalculator.stackAlloc(copy, STACK_SLOT_SIZE);
                Class<?> type = SharedUtils.primitiveCarrierForSize(copy, false);
                bindings.dup()
                        .vmLoad(storage, type)
                        .bufferStore(offset, type);
                offset += STACK_SLOT_SIZE;
            }
        }

        abstract List<Binding> getBindings(Class<?> carrier, MemoryLayout layout);

        abstract List<Binding> getIndirectBindings();
    }

    class UnboxBindingCalculator extends BindingCalculator {
        UnboxBindingCalculator(boolean forArguments) {
            super(forArguments);
        }

        @Override
        List<Binding> getIndirectBindings() {
            return Binding.builder()
                .unboxAddress()
                .vmStore(INDIRECT_RESULT, long.class)
                .build();
        }

        @Override
        List<Binding> getBindings(Class<?> carrier, MemoryLayout layout) {
            TypeClass argumentClass = TypeClass.classifyLayout(layout);
            Binding.Builder bindings = Binding.builder();
            switch (argumentClass) {
                case STRUCT_REGISTER: {
                    assert carrier == MemorySegment.class;
                    VMStorage[] regs = storageCalculator.regAlloc(
                        StorageClasses.INTEGER, layout);
                    if (regs != null) {
                        int regIndex = 0;
                        long offset = 0;
                        while (offset < layout.byteSize()) {
                            final long copy = Math.min(layout.byteSize() - offset, 8);
                            VMStorage storage = regs[regIndex++];
                            boolean useFloat = storage.type() == StorageClasses.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(copy, useFloat);
                            if (offset + copy < layout.byteSize()) {
                                bindings.dup();
                            }
                            bindings.bufferLoad(offset, type)
                                    .vmStore(storage, type);
                            offset += copy;
                        }
                    } else {
                        spillStructUnbox(bindings, layout);
                    }
                    break;
                }
                case STRUCT_REFERENCE: {
                    assert carrier == MemorySegment.class;
                    bindings.copy(layout)
                            .unboxAddress(MemorySegment.class);
                    VMStorage storage = storageCalculator.nextStorage(
                        StorageClasses.INTEGER, AArch64.C_POINTER);
                    bindings.vmStore(storage, long.class);
                    break;
                }
                case STRUCT_HFA: {
                    assert carrier == MemorySegment.class;
                    GroupLayout group = (GroupLayout)layout;
                    VMStorage[] regs = storageCalculator.regAlloc(
                        StorageClasses.VECTOR, group.memberLayouts().size());
                    if (regs != null) {
                        long offset = 0;
                        for (int i = 0; i < group.memberLayouts().size(); i++) {
                            VMStorage storage = regs[i];
                            final long size = group.memberLayouts().get(i).byteSize();
                            boolean useFloat = storage.type() == StorageClasses.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(size, useFloat);
                            if (i + 1 < group.memberLayouts().size()) {
                                bindings.dup();
                            }
                            bindings.bufferLoad(offset, type)
                                    .vmStore(storage, type);
                            offset += size;
                        }
                    } else {
                        spillStructUnbox(bindings, layout);
                    }
                    break;
                }
                case POINTER: {
                    bindings.unboxAddress(carrier);
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageClasses.INTEGER, layout);
                    bindings.vmStore(storage, long.class);
                    break;
                }
                case INTEGER: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageClasses.INTEGER, layout);
                    bindings.vmStore(storage, carrier);
                    break;
                }
                case FLOAT: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageClasses.VECTOR, layout);
                    bindings.vmStore(storage, carrier);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Unhandled class " + argumentClass);
            }
            return bindings.build();
        }
    }

    class BoxBindingCalculator extends BindingCalculator {
        BoxBindingCalculator(boolean forArguments) {
            super(forArguments);
        }

        @Override
        List<Binding> getIndirectBindings() {
            return Binding.builder()
                .vmLoad(INDIRECT_RESULT, long.class)
                .boxAddress()
                .build();
        }

        @Override
        List<Binding> getBindings(Class<?> carrier, MemoryLayout layout) {
            TypeClass argumentClass = TypeClass.classifyLayout(layout);
            Binding.Builder bindings = Binding.builder();
            switch (argumentClass) {
                case STRUCT_REGISTER: {
                    assert carrier == MemorySegment.class;
                    bindings.allocate(layout);
                    VMStorage[] regs = storageCalculator.regAlloc(
                        StorageClasses.INTEGER, layout);
                    if (regs != null) {
                        int regIndex = 0;
                        long offset = 0;
                        while (offset < layout.byteSize()) {
                            final long copy = Math.min(layout.byteSize() - offset, 8);
                            VMStorage storage = regs[regIndex++];
                            bindings.dup();
                            boolean useFloat = storage.type() == StorageClasses.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(copy, useFloat);
                            bindings.vmLoad(storage, type)
                                    .bufferStore(offset, type);
                            offset += copy;
                        }
                    } else {
                        spillStructBox(bindings, layout);
                    }
                    break;
                }
                case STRUCT_REFERENCE: {
                    assert carrier == MemorySegment.class;
                    VMStorage storage = storageCalculator.nextStorage(
                        StorageClasses.INTEGER, AArch64.C_POINTER);
                    bindings.vmLoad(storage, long.class)
                            .boxAddress()
                            .toSegment(layout);
                    break;
                }
                case STRUCT_HFA: {
                    assert carrier == MemorySegment.class;
                    bindings.allocate(layout);
                    GroupLayout group = (GroupLayout)layout;
                    VMStorage[] regs = storageCalculator.regAlloc(
                        StorageClasses.VECTOR, group.memberLayouts().size());
                    if (regs != null) {
                        long offset = 0;
                        for (int i = 0; i < group.memberLayouts().size(); i++) {
                            VMStorage storage = regs[i];
                            final long size = group.memberLayouts().get(i).byteSize();
                            boolean useFloat = storage.type() == StorageClasses.VECTOR;
                            Class<?> type = SharedUtils.primitiveCarrierForSize(size, useFloat);
                            bindings.dup()
                                    .vmLoad(storage, type)
                                    .bufferStore(offset, type);
                            offset += size;
                        }
                    } else {
                        spillStructBox(bindings, layout);
                    }
                    break;
                }
                case POINTER: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageClasses.INTEGER, layout);
                    bindings.vmLoad(storage, long.class)
                            .boxAddress();
                    break;
                }
                case INTEGER: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageClasses.INTEGER, layout);
                    bindings.vmLoad(storage, carrier);
                    break;
                }
                case FLOAT: {
                    VMStorage storage =
                        storageCalculator.nextStorage(StorageClasses.VECTOR, layout);
                    bindings.vmLoad(storage, carrier);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Unhandled class " + argumentClass);
            }
            return bindings.build();
        }
    }
}
