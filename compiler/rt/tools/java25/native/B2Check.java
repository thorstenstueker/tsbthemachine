import java.security.spec.MGF1ParameterSpec;
import java.util.SplittableRandom;

/**
 * The members added on 03.10.2026, on the real library.
 *
 * <h2>The emoji predicates, and why they are not native</h2>
 *
 * <p>Every other character predicate in {@code Character} is a native call into ICU4C. These seven
 * go through {@code android.icu} — ICU4J, the Java side — which costs no rebuild of the native
 * runtime for seven platform slices. That was only possible because the question
 * {@code CharacterCheck} has been asking on every run finally answered yes: measured on an AOT
 * binary after the locale data went in,
 *
 * <pre>
 *     ICU4J on Mac OS X aarch64: U+1F600 emoji=true, 'A' emoji=false, 'A' alphabetic=true
 * </pre>
 *
 * <p>It had not always. ICU4J's time zones were empty on an iPhone while the native ICU4C had all
 * of them, which is what {@code OlsonZoneRulesProvider} exists for — so "ICU4J works here" is a
 * platform fact and not a given, and {@code CharacterCheck} keeps printing it.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>JDK 25, asked directly. The binary16 conversions especially: {@code floatToFloat16(0.1f)} is
 * not a number anybody derives by hand, and the subnormal boundary at 2^-24 is exactly where a
 * conversion written from the specification goes wrong.
 */
public class B2Check {

    static int checked;
    static int failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    static void ok(String what, boolean condition) {
        checked++;
        if (!condition) {
            failed++;
            System.out.println("  MISMATCH " + what);
        }
    }

    static void emoji() {
        // U+1F600 GRINNING FACE: an emoji, with emoji presentation, and pictographic.
        ok("grinning face is an emoji", Character.isEmoji(0x1F600));
        ok("grinning face has emoji presentation", Character.isEmojiPresentation(0x1F600));
        ok("grinning face is extended pictographic", Character.isExtendedPictographic(0x1F600));

        // U+1F3FB EMOJI MODIFIER FITZPATRICK TYPE-1-2: a modifier, not a base.
        ok("skin tone is an emoji modifier", Character.isEmojiModifier(0x1F3FB));
        ok("skin tone is an emoji component", Character.isEmojiComponent(0x1F3FB));
        ok("skin tone is not a modifier base", !Character.isEmojiModifierBase(0x1F3FB));

        // U+1F44D THUMBS UP: a base for those modifiers.
        ok("thumbs up is a modifier base", Character.isEmojiModifierBase(0x1F44D));

        // '#' is an emoji *component* and an emoji, and has no emoji presentation by default —
        // the case that separates the four predicates from one another.
        ok("hash is an emoji", Character.isEmoji('#'));
        ok("hash is an emoji component", Character.isEmojiComponent('#'));
        ok("hash has no emoji presentation", !Character.isEmojiPresentation('#'));

        // And a letter, which is none of them.
        ok("A is not an emoji", !Character.isEmoji('A'));
        ok("A is not pictographic", !Character.isExtendedPictographic('A'));
    }

    static void namen() {
        eq("codePointOf a letter", Character.codePointOf("LATIN SMALL LETTER A"), 0x61);
        eq("codePointOf a space", Character.codePointOf("SPACE"), 0x20);
        eq("codePointOf an emoji", Character.codePointOf("GRINNING FACE"), 0x1F600);

        checked++;
        try {
            Character.codePointOf("NO SUCH CHARACTER AT ALL");
            failed++;
            System.out.println("  MISMATCH codePointOf(nonsense): threw nothing");
        } catch (IllegalArgumentException expected) {
            // what JDK 25 does
        }
    }

    static void gleitkomma() {
        eq("Float.PRECISION", Float.PRECISION, 24);

        // Exact values first: these have an exact binary16 and must round-trip.
        eq("float16 of 1.0", Float.floatToFloat16(1.0f), (short) 0x3c00);
        eq("float16 of -2.0", Float.floatToFloat16(-2.0f), (short) 0xc000);
        eq("float16 of 0.0", Float.floatToFloat16(0.0f), (short) 0x0000);
        eq("float16 of -0.0", Float.floatToFloat16(-0.0f), (short) 0x8000);
        eq("float from 1.0", Float.float16ToFloat((short) 0x3c00), 1.0f);
        eq("float from -2.0", Float.float16ToFloat((short) 0xc000), -2.0f);

        // Rounding, which is where a hand-written conversion differs: 0.1 has no exact binary16.
        eq("float16 of 0.1", Float.floatToFloat16(0.1f), (short) 0x2e66);
        eq("float back from that", Float.float16ToFloat((short) 0x2e66), 0.0999755859375f);

        // The boundaries. 65504 is the largest binary16; anything above is infinity.
        eq("float16 of 65504", Float.floatToFloat16(65504.0f), (short) 0x7bff);
        eq("float16 of 65536", Float.floatToFloat16(65536.0f), (short) 0x7c00);
        eq("float16 of infinity", Float.floatToFloat16(Float.POSITIVE_INFINITY), (short) 0x7c00);
        eq("float from infinity", Float.float16ToFloat((short) 0x7c00), Float.POSITIVE_INFINITY);

        // Subnormals: 2^-24 is the smallest, and half of it rounds to zero.
        eq("float16 of 2^-24", Float.floatToFloat16(0x1p-24f), (short) 0x0001);
        eq("float16 of 2^-25", Float.floatToFloat16(0x1p-25f), (short) 0x0000);
        eq("float from the smallest subnormal", Float.float16ToFloat((short) 0x0001), 0x1p-24f);
        eq("float16 of 2^-14", Float.floatToFloat16(0x1p-14f), (short) 0x0400);

        // NaN stays NaN, which a shift alone does not guarantee.
        ok("float16 of NaN is a NaN",
           Float.isNaN(Float.float16ToFloat(Float.floatToFloat16(Float.NaN))));
    }

    static void zufall() {
        // Determined by the seed, so the bytes are exactly reproducible — and the same two calls
        // on JDK 25 produce the same array, which is what makes this an assertion and not a smoke
        // test. Lengths either side of eight, because the tail loop is the part that writes past
        // the end when it is wrong.
        for (int laenge : new int[] {0, 1, 7, 8, 9, 16, 17}) {
            byte[] bytes = new byte[laenge];
            new SplittableRandom(42).nextBytes(bytes);
            byte[] erwartet = new byte[laenge];
            new SplittableRandom(42).nextBytes(erwartet);
            ok("nextBytes is repeatable at " + laenge, java.util.Arrays.equals(bytes, erwartet));
        }

        // Not all zero, for a length where that would otherwise go unnoticed.
        byte[] viele = new byte[64];
        new SplittableRandom(7).nextBytes(viele);
        boolean etwas = false;
        for (byte b : viele) {
            if (b != 0) {
                etwas = true;
                break;
            }
        }
        ok("nextBytes writes something", etwas);

        // The exact first eight bytes for seed 42, from JDK 25.
        byte[] acht = new byte[8];
        new SplittableRandom(42).nextBytes(acht);
        long alsLong = 0;
        for (int i = 7; i >= 0; i--) {
            alsLong = (alsLong << 8) | (acht[i] & 0xFFL);
        }
        eq("nextBytes agrees with nextLong", alsLong, new SplittableRandom(42).nextLong());
    }

    static void digests() {
        eq("SHA3_224", MGF1ParameterSpec.SHA3_224.getDigestAlgorithm(), "SHA3-224");
        eq("SHA3_256", MGF1ParameterSpec.SHA3_256.getDigestAlgorithm(), "SHA3-256");
        eq("SHA3_384", MGF1ParameterSpec.SHA3_384.getDigestAlgorithm(), "SHA3-384");
        eq("SHA3_512", MGF1ParameterSpec.SHA3_512.getDigestAlgorithm(), "SHA3-512");
        eq("SHA512_224", MGF1ParameterSpec.SHA512_224.getDigestAlgorithm(), "SHA-512/224");
        eq("SHA512_256", MGF1ParameterSpec.SHA512_256.getDigestAlgorithm(), "SHA-512/256");
        // The ones that were already here, so that a rename of the old would show up as well.
        eq("SHA256 still", MGF1ParameterSpec.SHA256.getDigestAlgorithm(), "SHA-256");
    }

    public static void main(String[] args) {
        emoji();
        namen();
        gleitkomma();
        zufall();
        digests();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
