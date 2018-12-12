package org.embulk.spi.v0;

import java.util.List;
import org.embulk.spi.v0.config.ConfigDiff;
import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskReport;
import org.embulk.spi.v0.config.TaskSource;

public interface OutputPlugin {
    interface Control {
        List<TaskReport> run(TaskSource taskSource);
    }

    ConfigDiff transaction(ConfigSource config,
            Schema schema, int taskCount,
            OutputPlugin.Control control);

    ConfigDiff resume(TaskSource taskSource,
            Schema schema, int taskCount,
            OutputPlugin.Control control);

    void cleanup(TaskSource taskSource,
            Schema schema, int taskCount,
            List<TaskReport> successTaskReports);

    TransactionalPageOutput open(TaskSource taskSource, Schema schema, int taskIndex);
}
