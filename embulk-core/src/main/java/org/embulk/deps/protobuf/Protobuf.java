package org.embulk.deps.protobuf;

public interface Protobuf {
    public static Protobuf getInstance() {
        // TODO: Load via the custom ClassLoader.
        return null;
    }

    int getWireTypeVarint();
}
