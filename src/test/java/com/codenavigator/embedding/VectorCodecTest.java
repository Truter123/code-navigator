package com.codenavigator.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VectorCodecTest {

    @Test
    void roundTrip_preservesAllFloats() {
        float[] original = {0.1f, -0.5f, 1.0f, Float.MAX_VALUE, Float.MIN_VALUE, 0.0f};
        byte[] bytes = VectorCodec.toBytes(original);
        float[] restored = VectorCodec.toFloats(bytes);
        assertThat(restored).containsExactly(original);
    }

    @Test
    void roundTrip_emptyArray() {
        byte[] bytes = VectorCodec.toBytes(new float[0]);
        assertThat(bytes).isEmpty();
        assertThat(VectorCodec.toFloats(bytes)).isEmpty();
    }

    @Test
    void byteLength_isFourTimesFloatCount() {
        assertThat(VectorCodec.toBytes(new float[]{1.0f, 2.0f, 3.0f})).hasSize(12);
    }

    @Test
    void littleEndian_knownBytes() {
        // 1.0f in IEEE 754 little-endian = 0x00, 0x00, 0x80, 0x3F
        assertThat(VectorCodec.toBytes(new float[]{1.0f}))
            .containsExactly((byte)0x00, (byte)0x00, (byte)0x80, (byte)0x3F);
    }
}
