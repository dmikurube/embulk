package org.embulk.spi.v0;

import java.util.List;
import java.util.Objects;

/*
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.collect.ImmutableList;
import org.embulk.spi.type.Type;
*/

public interface Schema /* extends List<Column> */{
    public interface Builder {
        public Builder add(String name, Type type);

        public Schema build();
    }

    // @JsonValue
    public List<Column> getColumns();

    public int size();

    public int getColumnCount();

    public Column getColumn(int index);

    public String getColumnName(int index);

    public Type getColumnType(int index);

    public void visitColumns(ColumnVisitor visitor);

    public boolean isEmpty();

    public Column lookupColumn(String name);

    public int getFixedStorageSize();

    /*
    @Override
    public boolean equals(Object obj);

    @Override
    public int hashCode();

    @Override
    public String toString();
    */
}
