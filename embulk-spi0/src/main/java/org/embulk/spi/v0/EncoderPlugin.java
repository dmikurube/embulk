package org.embulk.spi.v0;

import org.embulk.spi.v0.config.ConfigSource;
import org.embulk.spi.v0.config.TaskSource;

public interface EncoderPlugin {
    interface Control {
        void run(TaskSource taskSource);
    }

    void transaction(ConfigSource config, EncoderPlugin.Control control);

    FileOutput open(TaskSource taskSource, FileOutput fileOutput);
}
