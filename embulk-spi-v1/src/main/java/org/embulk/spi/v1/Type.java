package org.embulk.spi.v1;

public interface Type {
    String getName();
    Class<?> getJavaType();
    byte getFixedStorageSize();
}
