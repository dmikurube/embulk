package org.embulk.spi.v0;

import org.embulk.spi.v0.config.TaskReport;

public interface TransactionalFileOutput extends Transactional, FileOutput {
    void nextFile();

    void add(Buffer buffer);

    void finish();

    void close();

    void abort();

    TaskReport commit();
}
