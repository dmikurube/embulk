package org.embulk.deps.airlift;

import org.embulk.deps.util.ImplLoader;

/**
 * Proxies callings to io.airlift.slice.Slices.
 *
 * <p>It works like a Singleton, just one instance in the entire Java runtime, not per Embulk's execution session.
 * Singleton is fine for this because use of the library should not vary per execution session.
 *
 * <p>Injecting a {@code Slice} generator through Guice could be a choice. But that is not chosen for the time being
 * because Guice usage is going to be cleaned up. Guice is not to be used until the cleanup has finished.
 */
public abstract class Slices {
    public abstract org.embulk.deps.airlift.Slice wrappedBuffer(final byte[] array, final int offset, final int length);

    public static synchronized void setClassLoader(final ClassLoader classLoaderNew) {
        if (classLoaderNew == null) {
            throw new IllegalArgumentException("ClassLoader for org.embulk.deps.airlift.SlicesImpl must not be null.");
        }
        if (classLoader != null) {
            throw new RuntimeException("ClassLoader for org.embulk.deps.airlift.SlicesImpl is set twice.");
        }
        classLoader = classLoaderNew;
    }

    public static Slices get() {
        return InitializeOnDemandHolder.impl;
    }

    private static class InitializeOnDemandHolder {
        public static final Slices impl = ImplLoader.loadImpl(
                "org.embulk.deps.airlift.SlicesImpl",
                classLoader,
                CONSTRUCTOR_TYPES,
                Slices.class);
    }

    private static final Class<?>[] CONSTRUCTOR_TYPES = {};

    private static ClassLoader classLoader = null;
}
