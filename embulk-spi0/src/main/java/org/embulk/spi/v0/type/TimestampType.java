package org.embulk.spi.type;

import java.time.Instant;

public class TimestampType extends AbstractType {
    static final TimestampType TIMESTAMP = new TimestampType();

    private TimestampType() {
        super("timestamp", Instant.class, 12);
    }
}
