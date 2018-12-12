package org.embulk.spi.v0;

public interface FileOutput extends AutoCloseable {
    void nextFile();

    void add(Buffer buffer);

    void finish();

    void close();
}
