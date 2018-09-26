package org.embulk.deps.protobuf;

import com.google.protobuf.WireFormat;

public class ProtobufImpl implements Protobuf {
    @Override
    public int getWireTypeVarint() {
        return WireFormat.WIRETYPE_VARINT;
    }
}
