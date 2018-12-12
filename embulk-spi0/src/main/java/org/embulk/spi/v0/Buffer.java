package org.embulk.spi.v0;

public interface Buffer {
    public byte[] array();

    public int offset();

    public Buffer offset(int offset);

    public int limit();

    public Buffer limit(int limit);

    public int capacity();

    public void setBytes(int index, byte[] source, int sourceIndex, int length);

    public void setBytes(int index, Buffer source, int sourceIndex, int length);

    public void getBytes(int index, byte[] dest, int destIndex, int length);

    public void getBytes(int index, Buffer dest, int destIndex, int length);

    public void release();
}
