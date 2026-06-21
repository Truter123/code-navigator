package com.codenavigator.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VectorCodec {

    private VectorCodec() {}

    /** Encode float[] to a little-endian byte[] (4 bytes per float). */
    public static byte[] toBytes(float[] vector) {
        if (vector.length == 0) return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(4 * vector.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) buf.putFloat(f);
        return buf.array();
    }

    /** Decode a little-endian byte[] back to float[]. */
    public static float[] toFloats(byte[] bytes) {
        if (bytes.length == 0) return new float[0];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = buf.getFloat();
        return result;
    }
}
