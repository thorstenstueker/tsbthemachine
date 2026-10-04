package probe;

import java.util.Random;

/**
 * {@code Integer.compress/expand} and {@code Long.compress/expand} against the JDK's own.
 *
 * <h2>Why these belong in this harness and not in a device check</h2>
 *
 * <p>They are Hacker's Delight parallel-prefix routines: five unrolled rounds of mask shuffling
 * for {@code compress}, and for {@code expand} the same thing written out longhand with a
 * different shift distance in each round. A transposed distance or a dropped {@code ~} does not
 * crash and does not look wrong — it produces a number of the right magnitude with the wrong bits
 * in it. Nothing but the JDK's own answer to the same question catches that.
 *
 * <p>So the transplanted copies are called beside {@code Integer.compress} and
 * {@code Long.compress} themselves, over the inputs where this kind of code breaks.
 *
 * <h2>The inputs, and why these</h2>
 *
 * <p>Random values with random masks are the bulk, because the routine's difficulty is spread
 * across the mask rather than concentrated at a boundary. But four classes of mask are tried
 * deliberately:
 *
 * <ul>
 *   <li><b>all ones and all zeros</b> — the identity and the annihilator, where an off-by-one in
 *       the round count shows;</li>
 *   <li><b>a single bit</b>, each of the 32 or 64 in turn, which is the smallest case that moves
 *       anything at all;</li>
 *   <li><b>alternating bits</b> ({@code 0x55555555}, {@code 0xAAAAAAAA}) — the worst case for a
 *       parallel prefix, because every round has work to do;</li>
 *   <li><b>the sign bit</b> on its own, because {@code >>>} and {@code >>} are one character
 *       apart and only this input tells them apart.</li>
 * </ul>
 *
 * <p>And the round trip: {@code compress(expand(i, m), m)} has to give back {@code i} masked
 * down to the number of bits in {@code m}. That is a property rather than a comparison, so it
 * would catch the two routines being wrong in the same direction — which copying both out of one
 * file makes possible.
 */
public final class BitsCheck {

    static int checked;
    static int failed;

    static void eq(String what, long got, long want) {
        checked++;
        if (got != want) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + Long.toHexString(got)
                                       + ", wanted " + Long.toHexString(want));
        }
    }

    static void einInt(int i, int mask) {
        eq("Integer.compress(" + Integer.toHexString(i) + ", " + Integer.toHexString(mask) + ")",
           IntegerBits.compress(i, mask), Integer.compress(i, mask));
        eq("Integer.expand(" + Integer.toHexString(i) + ", " + Integer.toHexString(mask) + ")",
           IntegerBits.expand(i, mask), Integer.expand(i, mask));

        // The round trip, as a property: expanding into a mask and compressing back out of it
        // returns what went in, as far as the mask had room for.
        int bits = Integer.bitCount(mask);
        int masked = bits >= 32 ? i : (i & ((1 << bits) - 1));
        eq("Integer round trip for mask " + Integer.toHexString(mask),
           IntegerBits.compress(IntegerBits.expand(i, mask), mask), masked);
    }

    static void einLong(long i, long mask) {
        eq("Long.compress(" + Long.toHexString(i) + ", " + Long.toHexString(mask) + ")",
           LongBits.compress(i, mask), Long.compress(i, mask));
        eq("Long.expand(" + Long.toHexString(i) + ", " + Long.toHexString(mask) + ")",
           LongBits.expand(i, mask), Long.expand(i, mask));

        int bits = Long.bitCount(mask);
        long masked = bits >= 64 ? i : (i & ((1L << bits) - 1L));
        eq("Long round trip for mask " + Long.toHexString(mask),
           LongBits.compress(LongBits.expand(i, mask), mask), masked);
    }

    public static void main(String[] args) {
        int[] intMasks = {0, -1, 1, Integer.MIN_VALUE, 0x55555555, 0xAAAAAAAA, 0x0F0F0F0F,
                          0xFFFF0000, 0x0000FFFF, 7, 0xFF00FF00};
        int[] intValues = {0, -1, 1, -1 >>> 1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                           0x12345678, 0xDEADBEEF};

        for (int mask : intMasks) {
            for (int value : intValues) {
                einInt(value, mask);
            }
        }
        // Every single bit as a mask, in turn.
        for (int bit = 0; bit < 32; bit++) {
            einInt(0xDEADBEEF, 1 << bit);
        }

        long[] longMasks = {0L, -1L, 1L, Long.MIN_VALUE, 0x5555555555555555L,
                            0xAAAAAAAAAAAAAAAAL, 0x0F0F0F0F0F0F0F0FL, 0xFFFFFFFF00000000L,
                            0x00000000FFFFFFFFL, 7L};
        long[] longValues = {0L, -1L, 1L, -1L >>> 1, Long.MIN_VALUE, Long.MAX_VALUE,
                             0x123456789ABCDEF0L, 0xDEADBEEFCAFEBABEL};

        for (long mask : longMasks) {
            for (long value : longValues) {
                einLong(value, mask);
            }
        }
        for (int bit = 0; bit < 64; bit++) {
            einLong(0xDEADBEEFCAFEBABEL, 1L << bit);
        }

        // And the bulk: random pairs, which is where a fault in a round nobody thought about
        // shows up. Fixed seed, so a failure can be reproduced.
        Random random = new Random(20261004L);
        for (int n = 0; n < 20000; n++) {
            einInt(random.nextInt(), random.nextInt());
            einLong(random.nextLong(), random.nextLong());
        }

        System.out.println("BitsCheck: " + checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
