package org.embulk.deps.airlift;

public class MockSlices extends org.embulk.deps.airlift.Slices {
    public MockSlices() {
    }

    @Override
    public org.embulk.deps.airlift.Slice wrappedBuffer(final byte[] array, final int offset, final int length) {
        final io.airlift.slice.Slice subst = io.airlift.slice.Slices.wrappedBuffer(array, offset, length);
        return new org.embulk.deps.airlift.SliceImpl(subst);
    }
}
