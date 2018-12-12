package org.embulk.spi.v0;

public interface PageOutput extends AutoCloseable {
    void add(Page page);

    void finish();

    void close();
}
