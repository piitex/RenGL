package me.piitex.engine.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RotationTest {
    /** A 3 by 2 picture whose pixels are numbered 0 to 5 in the first byte. */
    private static byte[] picture() {
        byte[] p = new byte[3 * 2 * 4];
        for (int i = 0; i < 6; i++) p[i * 4] = (byte) i;
        return p;
    }

    private static int[] firstBytes(byte[] p) {
        int[] out = new int[p.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = p[i * 4];
        return out;
    }

    @Test
    void readsTheTurnFromTheTransform() {
        assertEquals(0, Rotation.quarterTurns(1, 0));
        assertEquals(1, Rotation.quarterTurns(0, 1));
        assertEquals(2, Rotation.quarterTurns(-1, 0));
        assertEquals(3, Rotation.quarterTurns(0, -1));
    }

    @Test
    void quarterTurnPutsTheTopLeftAtTheTopRight() {
        // 0 1 2      3 0
        // 3 4 5  ->  4 1
        //            5 2
        byte[] out = new byte[24];
        Rotation.rotate(picture(), out, 3, 2, 1);
        assertArrayEquals(new int[]{3, 0, 4, 1, 5, 2}, firstBytes(out));
    }

    @Test
    void halfTurnReverses() {
        byte[] out = new byte[24];
        Rotation.rotate(picture(), out, 3, 2, 2);
        assertArrayEquals(new int[]{5, 4, 3, 2, 1, 0}, firstBytes(out));
    }

    @Test
    void threeQuartersPutsTheTopLeftAtTheBottomLeft() {
        // 0 1 2      2 5
        // 3 4 5  ->  1 4
        //            0 3
        byte[] out = new byte[24];
        Rotation.rotate(picture(), out, 3, 2, 3);
        assertArrayEquals(new int[]{2, 5, 1, 4, 0, 3}, firstBytes(out));
    }

    @Test
    void fourQuartersIsNoTurn() {
        byte[] out = new byte[24];
        Rotation.rotate(picture(), out, 3, 2, 4);
        assertArrayEquals(picture(), out);
    }
}
