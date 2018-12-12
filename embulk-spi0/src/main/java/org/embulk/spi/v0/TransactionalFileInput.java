package org.embulk.spi.v0;

import org.embulk.spi.v0.config.TaskReport;

public interface TransactionalFileInput extends Transactional, FileInput {
    Buffer poll();

    boolean nextFile();

    void close();

    void abort();

    TaskReport commit();
}
