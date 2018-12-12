package org.embulk.spi.v0;

import org.embulk.spi.v0.config.TaskReport;

public interface Transactional {
    void abort();

    TaskReport commit();
}
