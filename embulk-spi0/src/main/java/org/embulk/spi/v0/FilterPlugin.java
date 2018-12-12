package org.embulk.spi;

import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskSource;

public interface FilterPlugin {
    interface Control {
        void run(TaskSource taskSource, Schema outputSchema);
    }

    void transaction(ConfigSource config, Schema inputSchema,
            FilterPlugin.Control control);

    PageOutput open(TaskSource taskSource, Schema inputSchema,
            Schema outputSchema, PageOutput output);
}
