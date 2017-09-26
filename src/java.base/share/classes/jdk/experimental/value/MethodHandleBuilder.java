/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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

package jdk.experimental.value;

import jdk.experimental.bytecode.BasicClassBuilder.BasicPoolHelper;
import jdk.experimental.bytecode.BasicClassBuilder.BasicTypeHelper;
import jdk.experimental.bytecode.ClassBuilder;
import jdk.experimental.bytecode.CodeBuilder;
import jdk.experimental.bytecode.Flag;
import jdk.experimental.bytecode.MethodBuilder;
import jdk.experimental.bytecode.PoolHelper;
import jdk.experimental.bytecode.TypeHelper;
import jdk.experimental.bytecode.TypeTag;
import jdk.experimental.bytecode.TypedCodeBuilder;
import jdk.experimental.value.MethodHandleBuilder.IsolatedMethodBuilder.IsolatedMethodPoolHelper;
import jdk.internal.misc.Unsafe;
import sun.security.action.GetPropertyAction;
import valhalla.shady.MinimalValueTypes_1_0;
import valhalla.shady.ValueTypeHolder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility class for building method handles.
 */
public class MethodHandleBuilder {

    static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static final boolean ENABLE_POOL_PATCHES;

    static {
        Properties props = GetPropertyAction.privilegedGetProperties();
        ENABLE_POOL_PATCHES = Boolean.parseBoolean(
                props.getProperty("valhalla.enablePoolPatches"));
    }

    public static MethodHandle loadCode(Lookup lookup, String name, MethodType type, Consumer<? super MethodHandleCodeBuilder<?>> builder) {
        return loadCode(lookup, name, name, type, builder);
    }

    public static MethodHandle loadCode(Lookup lookup, String className, String methodName, MethodType type, Consumer<? super MethodHandleCodeBuilder<?>> builder) {
        String descriptor = type.toMethodDescriptorString();
        return loadCode(lookup, className, methodName, descriptor, MethodHandleCodeBuilder::new,
                    clazz -> {
                        try {
                            return lookup.findStatic(clazz, methodName, MethodType.fromMethodDescriptorString(descriptor, lookup.lookupClass().getClassLoader()));
                        } catch (ReflectiveOperationException ex) {
                            throw new IllegalStateException(ex);
                        }
                    },
                    builder);
    }

    protected static <Z, C extends CodeBuilder<Class<?>, String, byte[], ?>> Z loadCode(
            Lookup lookup, String className, String methodName, String type,
            Function<MethodBuilder<Class<?>, String, byte[]>, ? extends C> builderFunc,
            Function<Class<?>, Z> resFunc, Consumer<? super C> builder) {

        IsolatedMethodBuilder isolatedMethodBuilder = new IsolatedMethodBuilder(className, lookup);
        isolatedMethodBuilder
                .withSuperclass(Object.class)
                .withMajorVersion(52)
                .withMinorVersion(0)
                .withFlags(Flag.ACC_PUBLIC)
                .withMethod(methodName, type, M ->
                        M.withFlags(Flag.ACC_STATIC, Flag.ACC_PUBLIC)
                                .withCode(builderFunc, builder));

        try {
            byte[] barr = isolatedMethodBuilder.build();
            MinimalValueTypes_1_0.maybeDump(className, barr.clone());
            Class<?> clazz = UNSAFE.defineAnonymousClass(lookup.lookupClass(), barr, isolatedMethodBuilder.patches());
            UNSAFE.ensureClassInitialized(clazz);
            return resFunc.apply(clazz);
        } catch (Throwable e) {
             throw new IllegalStateException(e);
        }
    }

    public static class IsolatedMethodBuilder extends ClassBuilder<Class<?>, String, IsolatedMethodBuilder> {

        private static final Class<?> THIS_CLASS = new Object() { }.getClass();

        public IsolatedMethodBuilder(String clazz, Lookup lookup) {
            super(ENABLE_POOL_PATCHES ?
                            new IsolatedMethodPatchingPoolHelper(clazz) :
                            new IsolatedMethodPoolHelper(clazz),
                  new IsolatedMethodTypeHelper(lookup));
            withThisClass(THIS_CLASS);
        }

        public Class<?> thisClass() {
            return THIS_CLASS;
        }

        Object[] patches() {
            return ((IsolatedMethodPoolHelper)poolHelper).patches();
        }

        static class IsolatedMethodTypeHelper implements TypeHelper<Class<?>, String> {

            BasicTypeHelper basicTypeHelper = new BasicTypeHelper();
            Lookup lookup;

            IsolatedMethodTypeHelper(Lookup lookup) {
                this.lookup = lookup;
            }

            @Override
            public String elemtype(String s) {
                return basicTypeHelper.elemtype(s);
            }

            @Override
            public String arrayOf(String s) {
                return basicTypeHelper.arrayOf(s);
            }

            @Override
            public Iterator<String> parameterTypes(String s) {
                return basicTypeHelper.parameterTypes(s);
            }

            @Override
            public String fromTag(TypeTag tag) {
                return basicTypeHelper.fromTag(tag);
            }

            @Override
            public String returnType(String s) {
                return basicTypeHelper.returnType(s);
            }

            @Override
            public String type(Class<?> aClass) {
                if (aClass.isArray()) {
                    return aClass.getName().replaceAll("\\.", "/");
                } else {
                    return MinimalValueTypes_1_0.isValueType(aClass) ?
                            "Q" + aClass.getName().replaceAll("\\.", "/") + ";" :
                            "L" + aClass.getName().replaceAll("\\.", "/") + ";";
                }
            }

            @Override
            public Class<?> symbol(String desc) {
                if (desc.endsWith("$Value;")) {
                    //value class - avoid Class.forName on the DVT
                    try {
                        int numDims = 0;
                        while (desc.startsWith("[")) {
                            desc = desc.substring(1);
                            numDims++;
                        }
                        Class<?> box = Class.forName(basicTypeHelper.symbol(desc)
                                .replaceAll("/", ".")
                                .replaceAll("\\$Value", ""), true, lookup.lookupClass().getClassLoader());
                        ValueTypeHolder<?> vt = MinimalValueTypes_1_0.getValueFor(box);
                        return numDims == 0 ?
                                vt.valueClass() : vt.arrayValueClass(numDims);
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                } else {
                    try {
                        if (desc.startsWith("[")) {
                           return Class.forName(desc.replaceAll("/", "."), true, lookup.lookupClass().getClassLoader());
                        } else {
                           return Class.forName(basicTypeHelper.symbol(desc).replaceAll("/", "."), true, lookup.lookupClass().getClassLoader());
                        }
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                }
            }

            @Override
            public TypeTag tag(String s) {
                return basicTypeHelper.tag(s);
            }

            @Override
            public Class<?> symbolFrom(String s) {
                return symbol(s);
            }

            @Override
            public String commonSupertype(String t1, String t2) {
                return basicTypeHelper.commonSupertype(t1, t2);
            }

            @Override
            public String nullType() {
                return basicTypeHelper.nullType();
            }
        }

        static class IsolatedMethodPoolHelper implements PoolHelper<Class<?>, String, byte[]> {
            BasicPoolHelper basicPoolHelper = new BasicPoolHelper();
            String clazz;

            IsolatedMethodPoolHelper(String clazz) {
                this.clazz = clazz;
            }

            String from(Class<?> c) {
                String name;
                boolean isValue = MinimalValueTypes_1_0.isValueType(c);
                if (c == THIS_CLASS) {
                    //THIS_CLASS cannot be a DVT (by construction) - never mangle
                    name = clazz;
                } else {
                    name = isValue ?
                            MinimalValueTypes_1_0.mangleValueClassName(c.getName()) :
                            c.getName();
                }
                return name.replaceAll("\\.", "/");
            }

            @Override
            public int putClass(Class<?> symbol) {
                return basicPoolHelper.putClass(from(symbol));
            }

            @Override
            public int putFieldRef(Class<?> owner, CharSequence name, String type) {
                return basicPoolHelper.putFieldRef(from(owner), name, type);
            }

            @Override
            public int putMethodRef(Class<?> owner, CharSequence name, String type, boolean isInterface) {
                return basicPoolHelper.putMethodRef(from(owner), name, type, isInterface);
            }

            @Override
            public int putUtf8(CharSequence s) {
                return basicPoolHelper.putUtf8(s);
            }

            @Override
            public int putType(String s) {
                return basicPoolHelper.putType(s);
            }

            @Override
            public int putValue(Object v) {
                return basicPoolHelper.putValue(v);
            }

            @Override
            public int putMethodType(String s) {
                return basicPoolHelper.putMethodType(s);
            }

            @Override
            public int putHandle(int refKind, Class<?> owner, CharSequence name, String type) {
                return basicPoolHelper.putHandle(refKind, from(owner), name, type);
            }

            @Override
            public int putInvokeDynamic(CharSequence invokedName, String invokedType, int bskKind, Class<?> bsmClass, CharSequence bsmName, String bsmType, Object... staticArgs) {
                return basicPoolHelper.putInvokeDynamic(invokedName, invokedType, bskKind, from(bsmClass), bsmName, bsmType, staticArgs);
            }

            @Override
            public int size() {
                return basicPoolHelper.size();
            }

            @Override
            public byte[] entries() {
                return basicPoolHelper.entries();
            }

            Object[] patches() {
                return null;
            }
        }

        @Override
        public byte[] build() {
            return super.build();
        }
    }

    static class IsolatedMethodPatchingPoolHelper extends IsolatedMethodPoolHelper {

        public IsolatedMethodPatchingPoolHelper(String clazz) {
            super(clazz);
        }

        Map<Object, CpPatch> cpPatches = new HashMap<>();
        int cph = 0;  // for counting constant placeholders

        static class CpPatch {

            final int index;
            final String placeholder;
            final Object value;

            CpPatch(int index, String placeholder, Object value) {
                this.index = index;
                this.placeholder = placeholder;
                this.value = value;
            }

            public String toString() {
                return "CpPatch/index="+index+",placeholder="+placeholder+",value="+value;
            }
        }

        @Override
        public int putValue(Object v) {
            if (v instanceof String || v instanceof Integer || v instanceof Float || v instanceof Double || v instanceof Long) {
                return basicPoolHelper.putValue(v);
            }
            assert (!v.getClass().isPrimitive()) : v;
            return patchPoolEntry(v); // CP patching support
        }

        int patchPoolEntry(Object v) {
            String cpPlaceholder = "CONSTANT_PLACEHOLDER_" + cph++;
            if (MinimalValueTypes_1_0.DUMP_CLASS_FILES) cpPlaceholder += " <<" + debugString(v) + ">>";
            if (cpPatches.containsKey(cpPlaceholder)) {
                throw new InternalError("observed CP placeholder twice: " + cpPlaceholder);
            }
            // insert placeholder in CP and remember the patch
            int index = basicPoolHelper.putValue(cpPlaceholder);  // TODO check if already in the constant pool
            cpPatches.put(cpPlaceholder, new CpPatch(index, cpPlaceholder, v));
            return index;
        }

        @Override
        Object[] patches() {
            int size = size();
            Object[] res = new Object[size];
            for (CpPatch p : cpPatches.values()) {
                if (p.index >= size)
                    throw new InternalError("bad cp patch index");
                res[p.index] = p.value;
            }
            return res;
        }

        private static String debugString(Object arg) {
            // @@@ Cannot crack open a MH like with InvokerByteCodeGenerator.debugString
            return arg.toString();
        }
    }

    public static class MethodHandleCodeBuilder<T extends MethodHandleCodeBuilder<T>> extends TypedCodeBuilder<Class<?>, String, byte[], T> {

        BasicTypeHelper basicTypeHelper = new BasicTypeHelper();

        public MethodHandleCodeBuilder(jdk.experimental.bytecode.MethodBuilder<Class<?>, String, byte[]> methodBuilder) {
            super(methodBuilder);
        }

        TypeTag getTagType(String s) {
            return basicTypeHelper.tag(s);
        }

        public T ifcmp(String s, CondKind cond, CharSequence label) {
            return super.ifcmp(getTagType(s), cond, label);
        }

        public T return_(String s) {
            return super.return_(getTagType(s));
        }
    }
}
