package org.embulk.spi.v0;

import java.util.List;
import org.embulk.spi.v0.config.ConfigDiff;
import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskReport;
import org.embulk.spi.v0.config.TaskSource;

public interface InputPlugin {
    interface Control {
        List<TaskReport> run(TaskSource taskSource,
                Schema schema, int taskCount);
    }

    ConfigDiff transaction(ConfigSource config,
            InputPlugin.Control control);

    ConfigDiff resume(TaskSource taskSource,
            Schema schema, int taskCount,
            InputPlugin.Control control);

    void cleanup(TaskSource taskSource,
            Schema schema, int taskCount,
            List<TaskReport> successTaskReports);

    TaskReport run(TaskSource taskSource,
            Schema schema, int taskIndex,
            PageOutput output);

    ConfigDiff guess(ConfigSource config);
}
