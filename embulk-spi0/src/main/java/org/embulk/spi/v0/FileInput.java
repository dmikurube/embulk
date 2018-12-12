package org.embulk.spi.v0;

public interface FileInput extends AutoCloseable {
    boolean nextFile();

    Buffer poll();

    void close();
}
