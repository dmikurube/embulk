package org.embulk.spi.v0;

import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskSource;

public interface FormatterPlugin {
    interface Control {
        void run(TaskSource taskSource);
    }

    void transaction(ConfigSource config, Schema schema,
            FormatterPlugin.Control control);

    PageOutput open(TaskSource taskSource, Schema schema,
            FileOutput output);
}
