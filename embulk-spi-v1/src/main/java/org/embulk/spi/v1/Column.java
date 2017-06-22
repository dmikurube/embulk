package org.embulk.spi;

public abstract class Column
{
    public abstract int getIndex();
    public abstract String getName();
    public abstract Type getType();
    public abstract boolean equals(Object obj);
    public abstract int hashCode();
    public abstract String toString();
}
