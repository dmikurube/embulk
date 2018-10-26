package org.embulk.deps.util;

import java.lang.reflect.Constructor;

public class ImplLoader {
    private ImplLoader() {
        // No instantiation.
    }

    public static <T> T loadImpl(
            final String nameImpl,
            final ClassLoader classLoader,
            final Class<?>[] constructorTypes,
            final Class<T> classProxy) {
        if (classLoader == null) {
            return null;
        }

        final Class<T> classImpl;
        try {
            @SuppressWarnings("unchecked")
            final Class<T> c = (Class<T>) (classLoader.loadClass(nameImpl));
            classImpl = c;
        } catch (final ClassNotFoundException ex) {
            throw new RuntimeException(ex);
        }

        final Constructor<T> constructor;
        try {
            constructor = classImpl.getConstructor(constructorTypes);
        } catch (final NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        }

        final T impl;
        try {
            impl = constructor.newInstance();
        } catch (final ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }

        return impl;
    }
}
