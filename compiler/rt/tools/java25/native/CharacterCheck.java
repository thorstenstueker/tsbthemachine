/**
 * Character's bidirectional isolate types, and whether ICU4J has character data here.
 *
 * <h2>The isolates were a wrong answer, not a missing constant</h2>
 *
 * Unicode 6.3 added four bidirectional types — LRI, RLI, FSI, PDI, the characters U+2066 to
 * U+2069 — and this library's {@code DIRECTIONALITY} table stopped at the eighteen that came
 * before them. {@code getDirectionality} falls back to {@code DIRECTIONALITY_UNDEFINED} for
 * anything past the end of that table, so the four answered {@code -1}. A program laying out
 * mixed Hebrew and Latin text got silence where it should have got an isolate.
 *
 * <b>ICU's order is not Java's, and that is the part worth pinning.</b> ICU numbers
 * {@code U_FIRST_STRONG_ISOLATE} 19 and {@code U_LEFT_TO_RIGHT_ISOLATE} 20; Java numbers them the
 * other way round. The table is a reordering and always was. Reading the two headers side by side
 * is exactly how LRI and FSI end up swapped, which is why the expected values below come from
 * JDK 25 instead.
 *
 * <h2>And the question this check exists to answer</h2>
 *
 * Whether {@code android.icu} — ICU4J, the Java side — has character data in an ahead-of-time
 * image. It is not an idle question: measured 03.10.2026 on an iPhone simulator, ICU4J's time
 * zones were <b>empty</b> there while the native ICU4C had everything, which is what
 * {@code OlsonZoneRulesProvider} exists for.
 *
 * <p>The seven emoji predicates of Java 21 — {@code isEmoji} and its siblings — would be one line
 * each through {@code UCharacter.hasBinaryProperty}, and a C function each through
 * {@code u_hasBinaryProperty} the way the rest of this class already goes. The first costs nothing
 * and the second costs a rebuild of the native runtime for seven platform slices. Which one is
 * right depends entirely on the answer below, so it is measured before anything is written.
 */
public class CharacterCheck {

    static int checked;
    static int failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    static void directionality() {
        // The four that were wrong. Values from JDK 25, asked directly.
        eq("U+2066 LRI", Character.getDirectionality(0x2066), 19);
        eq("U+2067 RLI", Character.getDirectionality(0x2067), 20);
        eq("U+2068 FSI", Character.getDirectionality(0x2068), 21);
        eq("U+2069 PDI", Character.getDirectionality(0x2069), 22);

        // The constants themselves, in case somebody renumbers them to match ICU.
        eq("LEFT_TO_RIGHT_ISOLATE", Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE, 19);
        eq("RIGHT_TO_LEFT_ISOLATE", Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE, 20);
        eq("FIRST_STRONG_ISOLATE", Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE, 21);
        eq("POP_DIRECTIONAL_ISOLATE", Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE, 22);

        // And the neighbours, so that a table extended at the wrong end reports itself.
        eq("U+202A LRE", Character.getDirectionality(0x202A), 14);
        eq("U+202C PDF", Character.getDirectionality(0x202C), 18);
        eq("A is left to right", Character.getDirectionality('A'), 0);
        eq("aleph is right to left", Character.getDirectionality(0x05D0), 1);
        eq("an unassigned code point", Character.getDirectionality(0x0378), -1);
    }

    /**
     * Does ICU4J answer about characters here?
     *
     * <p>Reported rather than asserted. The answer decides how the emoji predicates are built and
     * is not itself right or wrong — on an ahead-of-time image it may differ from this host, which
     * is the whole reason it is printed with the platform beside it.
     */
    static void icu4jData() {
        String platform = System.getProperty("os.name", "?") + " "
                          + System.getProperty("os.arch", "?");
        try {
            boolean emoji = android.icu.lang.UCharacter.hasBinaryProperty(
                    0x1F600, android.icu.lang.UProperty.EMOJI);
            boolean notEmoji = android.icu.lang.UCharacter.hasBinaryProperty(
                    'A', android.icu.lang.UProperty.EMOJI);
            boolean alphabetic = android.icu.lang.UCharacter.hasBinaryProperty(
                    'A', android.icu.lang.UProperty.ALPHABETIC);

            System.out.println("  ICU4J on " + platform + ": U+1F600 emoji=" + emoji
                               + ", 'A' emoji=" + notEmoji + ", 'A' alphabetic=" + alphabetic);
            // Useful either way: true here means ICU4J is an option on this platform, false means
            // the native route is the only one. It is not an assertion because the answer is
            // allowed to differ between a host binary and a phone.
        } catch (Throwable noIcu4j) {
            System.out.println("  ICU4J on " + platform + ": unusable — "
                               + noIcu4j.getClass().getName());
        }

        // What ICU4C answers, through the route Character already takes. This one is an assertion:
        // it is the library's own behaviour and has to hold.
        eq("isAlphabetic through ICU4C", Character.isAlphabetic('A'), true);
        eq("isIdeographic through ICU4C", Character.isIdeographic(0x4E00), true);
    }

    public static void main(String[] args) {
        directionality();
        icu4jData();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
