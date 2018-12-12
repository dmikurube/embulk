package org.embulk.spi.v0;

/*
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
*/
import java.util.Objects;
/*
import org.embulk.spi.type.BooleanType;
import org.embulk.spi.type.DoubleType;
import org.embulk.spi.type.JsonType;
import org.embulk.spi.type.LongType;
import org.embulk.spi.type.StringType;
import org.embulk.spi.type.TimestampType;
import org.embulk.spi.type.Type;
*/

public interface Column {
    public int getIndex();

    public String getName();

    public Type getType();

    public void visit(ColumnVisitor visitor);
}
