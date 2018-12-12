package org.embulk.spi.v0;

import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskSource;

public interface ParserPlugin {
    interface Control {
        void run(TaskSource taskSource, Schema schema);
    }

    void transaction(ConfigSource config, ParserPlugin.Control control);

    void run(TaskSource taskSource, Schema schema,
            FileInput input, PageOutput output);
}
