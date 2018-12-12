package org.embulk.spi.v0;

import java.util.List;
import org.embulk.spi.v0.config.ConfigDiff;
import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskReport;
import org.embulk.spi.v0.config.TaskSource;

public interface FileInputPlugin {
    interface Control {
        List<TaskReport> run(TaskSource taskSource,
                int taskCount);
    }

    ConfigDiff transaction(ConfigSource config,
            FileInputPlugin.Control control);

    ConfigDiff resume(TaskSource taskSource,
            int taskCount,
            FileInputPlugin.Control control);

    void cleanup(TaskSource taskSource,
            int taskCount,
            List<TaskReport> successTaskReports);

    TransactionalFileInput open(TaskSource taskSource,
            int taskIndex);
}
