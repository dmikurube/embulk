package org.embulk.spi.v0;

import java.util.List;
import org.embulk.spi.v0.config.ConfigDiff;
import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskReport;
import org.embulk.spi.v0.config.TaskSource;

public interface FileOutputPlugin {
    interface Control {
        List<TaskReport> run(TaskSource taskSource);
    }

    ConfigDiff transaction(ConfigSource config, int taskCount,
            FileOutputPlugin.Control control);

    ConfigDiff resume(TaskSource taskSource,
            int taskCount,
            FileOutputPlugin.Control control);

    void cleanup(TaskSource taskSource,
            int taskCount,
            List<TaskReport> successTaskReports);

    TransactionalFileOutput open(TaskSource taskSource, int taskIndex);
}
