package org.embulk.spi.v1;

import java.util.List;

public abstract class Schema {
    public abstract List<Column> getColumns();
    public abstract int size();
    public abstract int getColumnCount();
    public abstract Column getColumn(int index);
    public abstract String getColumnName(int index);
    public abstract Type getColumnType(int index);
    public abstract void visitColumns(ColumnVisitor visitor);
    public abstract boolean isEmpty();
    public abstract Column lookupColumn(String name);
    public abstract int getFixedStorageSize();

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();
}
