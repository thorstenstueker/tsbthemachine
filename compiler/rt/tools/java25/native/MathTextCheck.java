import java.math.BigInteger;
import java.util.Arrays;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.nio.ByteBuffer;

/**
 * BigInteger, the named regex groups and Deflater's buffer methods, on the real library.
 *
 * Sixteen members of Java 9 through 20. Three of them have arithmetic in them and the rest are
 * plumbing, and the plumbing is where the mistakes were.
 *
 * <h2>What is worth pinning</h2>
 *
 * <b>sqrt rounds down and never oscillates.</b> Newton's method on integers converges and then
 * flips between two neighbouring values forever; the loop has to stop at the smaller. 144 and 145
 * both answer 12, 2 and 3 both answer 1 — the second pair is the one a wrong stopping rule gets
 * wrong, because the first guess is already above the root there.
 *
 * <b>The ranged constructors disagree with the one they look like.</b> {@code new BigInteger(new
 * byte[0])} throws NumberFormatException; {@code new BigInteger(bytes, 2, 0)} is zero. Measured on
 * JDK 25, and the sort of difference nobody would guess at.
 *
 * <b>A detached MatchResult has to carry the group names.</b> {@code toMatchResult()} outlives the
 * matcher, and ICU can only resolve a name through the native matcher that is gone by then. If the
 * names were not copied in, {@code group("jahr")} could only throw — on an object that plainly
 * holds the group.
 *
 * <b>Deflater remembers the buffer rather than copying it.</b> After {@code setInput(ByteBuffer)}
 * the position is still 0 and {@code needsInput()} already says false; the position moves only
 * when something is actually compressed. A caller who reads the position in between, or hands the
 * same buffer on, sees the difference.
 *
 * <h2>Where the expected values come from</h2>
 *
 * Every one was run on JDK 25 first and copied from what it printed. The pattern scanner below is
 * the clearest case for that: what counts as a group and what does not — escapes, character
 * classes, lookbehind — is easy to be confident about and easy to get wrong.
 */
public class MathTextCheck {

    static int checked, failed;

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

    static void throwsToo(String what, Class<? extends Throwable> expected, Runnable r) {
        checked++;
        try {
            r.run();
            failed++;
            System.out.println("  MISMATCH " + what + ": threw nothing, wanted "
                               + expected.getName());
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                failed++;
                System.out.println("  MISMATCH " + what + ": threw " + t.getClass().getName()
                                   + ", wanted " + expected.getName());
            }
        }
    }

    // ---------------------------------------------------------------- BigInteger

    static void bigInteger() {
        eq("TWO", BigInteger.TWO, "2");
        eq("TWO is the constant, not a fresh object",
           BigInteger.TWO.equals(BigInteger.valueOf(2)), true);

        // A perfect square and its neighbour answer the same, which is what "rounded down" means.
        eq("sqrt(144)", new BigInteger("144").sqrt(), "12");
        eq("sqrt(145)", new BigInteger("145").sqrt(), "12");
        eq("sqrt(143)", new BigInteger("143").sqrt(), "11");

        // The small ones, where the first guess is already above the root and a wrong stopping
        // rule would run on or stop short.
        eq("sqrt(0)", BigInteger.ZERO.sqrt(), "0");
        eq("sqrt(1)", BigInteger.ONE.sqrt(), "1");
        eq("sqrt(2)", new BigInteger("2").sqrt(), "1");
        eq("sqrt(3)", new BigInteger("3").sqrt(), "1");
        eq("sqrt(4)", new BigInteger("4").sqrt(), "2");

        // Thirty digits, where the answer cannot come from a double.
        eq("sqrt of a thirty-digit number",
           new BigInteger("123456789012345678901234567890").sqrt(), "351364182882014");

        eq("sqrtAndRemainder(145)",
           Arrays.toString(new BigInteger("145").sqrtAndRemainder()), "[12, 1]");
        eq("sqrtAndRemainder(144)",
           Arrays.toString(new BigInteger("144").sqrtAndRemainder()), "[12, 0]");

        throwsToo("sqrt of a negative", ArithmeticException.class, new Runnable() {
            public void run() {
                new BigInteger("-4").sqrt();
            }
        });

        // The ranged constructors. b is 9,9,0,0,1,2,9 — the range 2..6 is 00 00 01 02 = 258.
        byte[] b = {9, 9, 0, 0, 1, 2, 9};
        eq("BigInteger(b, 2, 4)", new BigInteger(b, 2, 4), "258");
        eq("BigInteger(1, b, 2, 4)", new BigInteger(1, b, 2, 4), "258");

        // Two's complement inside the range: FF FE is -2, and the sign comes from the range's
        // first byte rather than from the array's.
        byte[] neg = {9, (byte) 0xFF, (byte) 0xFE, 9};
        eq("a negative range", new BigInteger(neg, 1, 2), "-2");

        // Zero length is zero here and an exception in the constructor without offsets. Measured.
        eq("an empty range is zero", new BigInteger(b, 2, 0), "0");
        eq("and with a signum too", new BigInteger(1, b, 2, 0), "0");
        throwsToo("while an empty array still throws", NumberFormatException.class,
                  new Runnable() {
                      public void run() {
                          new BigInteger(new byte[0]);
                      }
                  });

        throwsToo("a range past the end", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                new BigInteger(new byte[] {1, 2, 3}, 2, 9);
            }
        });
        throwsToo("a negative offset", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                new BigInteger(new byte[] {1, 2, 3}, -1, 2);
            }
        });
        throwsToo("signum 0 over non-zero bytes", NumberFormatException.class, new Runnable() {
            public void run() {
                new BigInteger(0, new byte[] {9, 9, 1, 2}, 2, 2);
            }
        });
        throwsToo("a signum that is not -1, 0 or 1", NumberFormatException.class, new Runnable() {
            public void run() {
                new BigInteger(2, new byte[] {1, 2}, 0, 2);
            }
        });

        eq("parallelMultiply agrees with multiply",
           new BigInteger("123456789").parallelMultiply(new BigInteger("987654321")),
           "121932631112635269");
    }

    // ---------------------------------------------------------------- named groups

    static void namedGroups() {
        Pattern p = Pattern.compile("(?<jahr>\\d{4})-(?<monat>\\d{2})");
        eq("the pattern lists its groups", p.namedGroups().toString(), "{jahr=1, monat=2}");

        Matcher m = p.matcher("2026-10");
        eq("hasMatch before anything was tried", m.hasMatch(), false);
        eq("find", m.find(), true);
        eq("hasMatch after a match", m.hasMatch(), true);
        eq("group by name", m.group("jahr"), "2026");
        eq("start by name", m.start("monat"), 5);
        eq("end by name", m.end("monat"), 7);

        // The detached result is the interesting one: the matcher is still there, but this object
        // has to answer on its own, and ICU's name lookup is gone with the native matcher.
        MatchResult r = m.toMatchResult();
        eq("a detached result knows the names", r.namedGroups().toString(), "{jahr=1, monat=2}");
        eq("and resolves them", r.group("jahr"), "2026");
        eq("including the offsets", r.start("monat") + ".." + r.end("monat"), "5..7");
        eq("a detached result always has a match", r.hasMatch(), true);

        throwsToo("a name the pattern does not have", IllegalArgumentException.class,
                  new Runnable() {
                      public void run() {
                          Pattern.compile("(?<a>x)").matcher("x").namedGroups().toString();
                          Matcher m2 = Pattern.compile("(?<a>x)").matcher("x");
                          m2.find();
                          m2.toMatchResult().group("b");
                      }
                  });

        eq("a pattern without names", Pattern.compile("\\d+").namedGroups().toString(), "{}");

        // The three the scanner has to get right, and each appears in real patterns.
        eq("an escaped parenthesis opens nothing",
           Pattern.compile("\\(?<a>x").namedGroups().toString(), "{}");
        eq("a character class holds no groups",
           Pattern.compile("[(?<a>]x").namedGroups().toString(), "{}");
        eq("lookbehind is not a group called '='",
           Pattern.compile("(?<=a)(?<b>x)").namedGroups().toString(), "{b=1}");
        eq("negative lookbehind neither",
           Pattern.compile("(?<!a)(?<b>x)").namedGroups().toString(), "{b=1}");

        // And the counting, which is the other half: a name maps to its group *number*, so every
        // plain parenthesis in front of it has to have been counted.
        eq("numbers count unnamed groups too",
           Pattern.compile("(a)(?:b)(?<c>d)").namedGroups().toString(), "{c=2}");
        eq("nested groups count outward in",
           Pattern.compile("((?<innen>a))").namedGroups().toString(), "{innen=2}");
    }

    // ---------------------------------------------------------------- Deflater

    static void deflater() throws Exception {
        Deflater d = new Deflater();
        ByteBuffer ein = ByteBuffer.wrap("hallo welt hallo welt".getBytes("UTF-8"));

        d.setInput(ein);
        // The buffer is remembered, not consumed — measured on JDK 25, and the whole reason
        // setInput keeps a reference instead of copying and advancing.
        eq("the position does not move yet", ein.position(), 0);
        eq("but there is input", d.needsInput(), false);

        d.finish();
        ByteBuffer aus = ByteBuffer.allocate(64);
        int n = d.deflate(aus);
        ok("something was written: " + n, n > 0);
        eq("the output position moved by what was written", aus.position(), n);
        eq("and now the input is spent", ein.position(), 21);
        eq("finished", d.finished(), true);

        // What came out has to be readable again — a compressor whose output cannot be inflated
        // is a compressor that passed every check above and is useless.
        byte[] gepackt = new byte[n];
        aus.flip();
        aus.get(gepackt);
        java.util.zip.Inflater i = new java.util.zip.Inflater();
        i.setInput(gepackt);
        byte[] zurueck = new byte[64];
        int wieViel = i.inflate(zurueck);
        eq("it inflates back to the original",
           new String(zurueck, 0, wieViel, "UTF-8"), "hallo welt hallo welt");
        i.end();

        d.close();
        // IllegalStateException, as the specification asks and as JDK 25 does. Until 01.10.2026
        // this library threw NullPointerException here — an Android inheritance that was merely
        // odd while the only way to close was end(), and misleading once try-with-resources made
        // the case ordinary. Inflater was changed with it; the two had the same fault.
        throwsToo("a closed deflater refuses to work", IllegalStateException.class,
                  new Runnable() {
                      public void run() {
                          Deflater zu = new Deflater();
                          zu.close();
                          zu.deflate(ByteBuffer.allocate(16));
                      }
                  });

        ok("Deflater is Closeable", new Deflater() instanceof java.io.Closeable);

        // A direct buffer has no array, so this is the path that copies — and it has to produce
        // the same bytes as the heap one.
        Deflater direkt = new Deflater();
        ByteBuffer dEin = ByteBuffer.allocateDirect(32);
        dEin.put("abcabcabcabc".getBytes("UTF-8")).flip();
        direkt.setInput(dEin);
        direkt.finish();
        ByteBuffer dAus = ByteBuffer.allocateDirect(64);
        int dn = direkt.deflate(dAus);
        ok("a direct buffer compresses too: " + dn, dn > 0);
        eq("and its input is spent", dEin.position(), 12);
        direkt.close();

        // The dictionary is consumed at once, unlike the input.
        Deflater mitWoerterbuch = new Deflater();
        ByteBuffer woerterbuch = ByteBuffer.wrap("hallo".getBytes("UTF-8"));
        mitWoerterbuch.setDictionary(woerterbuch);
        eq("a dictionary is read in full by the call", woerterbuch.remaining(), 0);
        mitWoerterbuch.close();
    }

    public static void main(String[] args) throws Exception {
        bigInteger();
        namedGroups();
        deflater();

        System.out.println("MathTextCheck: " + checked + " checked, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
