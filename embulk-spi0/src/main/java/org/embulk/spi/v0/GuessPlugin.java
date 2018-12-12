package org.embulk.spi.v0;

import org.embulk.spi.v0.config.ConfigDiff;
import org.embulk.spi.v0.config.ConfigSource;

public interface GuessPlugin {
    ConfigDiff guess(ConfigSource config, Buffer sample);
}
