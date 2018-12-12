package org.embulk.spi.v0;

import org.embulk.spi.v0.config.TaskReport;

public interface TransactionalPageOutput extends Transactional, PageOutput {
    void add(Page page);

    void finish();

    void close();

    void abort();

    TaskReport commit();
}
