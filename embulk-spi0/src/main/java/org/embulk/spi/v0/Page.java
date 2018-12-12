package org.embulk.spi;

import java.util.List;
import org.msgpack.value.ImmutableValue;

/**
 * Page is an in-process (in-JVM) container of data records.
 *
 * It serializes records to byte[] (in org.embulk.spi.Buffer) in order to:
 * A) Avoid slowness by handling many Java Objects
 * B) Avoid complexity by type-safe primitive arrays
 * C) Track memory consumption by records
 * D) Use off-heap memory
 *
 * (C) and (D) may not be so meaningful as of v0.7+ (or since earlier) as recent Embulk unlikely
 * allocates so many Pages at the same time. Recent Embulk is streaming-driven instead of
 * multithreaded queue-based.
 *
 * Page is NOT for inter-process communication. For multi-process execution such as MapReduce
 * Executor, the executor plugin takes responsibility about interoperable serialization.
 */
public interface Page {
    public Page setStringReferences(List<String> values);

    public Page setValueReferences(List<ImmutableValue> values);

    public List<String> getStringReferences();

    public List<ImmutableValue> getValueReferences();

    public String getStringReference(int index);

    public ImmutableValue getValueReference(int index);

    public void release();

    public Buffer buffer();
}
