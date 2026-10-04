import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.NoSuchElementException;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * The small additions of 04.10.2026, on the real library: {@code Checksum}'s two Java 9 defaults,
 * two pairs of causal exception constructors, and the key parameters on the RSA key specs.
 *
 * <h2>Why the Checksum half is the substantial one</h2>
 *
 * <p>{@code update(byte[])} is one line and could hardly be wrong. {@code update(ByteBuffer)} has
 * two branches and a postcondition, and the postcondition is the part that gets written wrong: the
 * buffer's <b>position must end at its limit and its limit must not change</b>. The array branch
 * reads through {@code array()} without moving the position at all, so it has to be set
 * afterwards; the direct branch moves the position itself by reading through it, so setting it
 * afterwards has to be harmless rather than wrong. An implementation that advanced instead of
 * setting would be correct for a direct buffer and silently wrong for a heap one.
 *
 * <p>And both branches have to produce the <em>same number</em> as
 * {@code update(array, offset, length)}, which is what makes a direct buffer testable at all.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Not from a table of CRCs. Each checksum is computed twice over the same bytes — once through
 * the three-argument method that was always there, once through the new ones — and the two
 * compared. A wrong constant in a test teaches nothing; two routes to one answer catch the fault
 * that actually happens, which is a wrong offset or a wrong length.
 */
public class ChecksumCheck {

    static int checked;
    static int failed;

    static void ok(String what, boolean condition) {
        checked++;
        if (!condition) {
            failed++;
            System.out.println("  MISMATCH " + what);
        }
    }

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    /** Bytes that are neither all the same nor a short run — a CRC distinguishes those badly. */
    static byte[] daten(int wieViel) {
        byte[] b = new byte[wieViel];
        for (int i = 0; i < wieViel; i++) {
            b[i] = (byte) ((i * 31 + 7) % 251);
        }
        return b;
    }

    static long dreiArgumente(Checksum c, byte[] b, int off, int len) {
        c.reset();
        c.update(b, off, len);
        return c.getValue();
    }

    static void einChecksum(String name, Checksum c) {
        byte[] b = daten(5000);              // past the 4096 scratch array, so the loop runs twice

        long wanted = dreiArgumente(c, b, 0, b.length);

        // update(byte[]) — the one-liner.
        c.reset();
        c.update(b);
        eq(name + " update(byte[])", c.getValue(), wanted);

        // A heap buffer: the array branch.
        ByteBuffer heap = ByteBuffer.wrap(b);
        c.reset();
        c.update(heap);
        eq(name + " update(heap buffer)", c.getValue(), wanted);
        eq(name + " heap position ended at the limit", heap.position(), heap.limit());
        eq(name + " heap limit unchanged", heap.limit(), b.length);

        // A direct buffer: the copying branch, and the one the 4096 scratch array exists for.
        ByteBuffer direct = ByteBuffer.allocateDirect(b.length);
        direct.put(b);
        direct.flip();
        c.reset();
        c.update(direct);
        eq(name + " update(direct buffer)", c.getValue(), wanted);
        eq(name + " direct position ended at the limit", direct.position(), direct.limit());

        // A slice, so that arrayOffset() is not zero. This is where an implementation that
        // forgot to add it reads the wrong bytes — and gets a plausible-looking number.
        ByteBuffer whole = ByteBuffer.wrap(b);
        whole.position(1000);
        whole.limit(4000);
        ByteBuffer slice = whole.slice();
        ok(name + " the slice has a non-zero arrayOffset", slice.arrayOffset() == 1000);
        c.reset();
        c.update(slice);
        eq(name + " update(a slice)", c.getValue(), dreiArgumente(c, b, 1000, 3000));

        // An empty buffer changes nothing and must not move anything either.
        ByteBuffer empty = ByteBuffer.wrap(b, 10, 0);
        c.reset();
        long before = c.getValue();
        c.update(empty);
        eq(name + " an empty buffer is a no-op", c.getValue(), before);
        eq(name + " an empty buffer's position", empty.position(), 10);
    }

    static void pruefsummen() {
        einChecksum("CRC32", new CRC32());
        einChecksum("Adler32", new Adler32());
        try {
            // Java 9, and it may not be here. Printed rather than asserted for that reason.
            Checksum c = (Checksum) Class.forName("java.util.zip.CRC32C").newInstance();
            einChecksum("CRC32C", c);
        } catch (Throwable notHere) {
            System.out.println("  CRC32C not on this runtime — the defaults were checked on the "
                                       + "two that are");
        }
    }

    static void ausnahmen() {
        Throwable cause = new IllegalStateException("underneath");

        NoSuchElementException both = new NoSuchElementException("gone", cause);
        eq("NoSuchElementException(s, cause) message", both.getMessage(), "gone");
        ok("NoSuchElementException(s, cause) cause", both.getCause() == cause);

        NoSuchElementException only = new NoSuchElementException(cause);
        ok("NoSuchElementException(cause) cause", only.getCause() == cause);
        // The detail message comes from the cause's toString, which is what the two-argument
        // Throwable constructor has always done. A null message here would be a different class.
        eq("NoSuchElementException(cause) message", only.getMessage(), cause.toString());

        InvalidParameterException ipBoth = new InvalidParameterException("bad", cause);
        eq("InvalidParameterException(s, cause) message", ipBoth.getMessage(), "bad");
        ok("InvalidParameterException(s, cause) cause", ipBoth.getCause() == cause);

        InvalidParameterException ipOnly = new InvalidParameterException(cause);
        ok("InvalidParameterException(cause) cause", ipOnly.getCause() == cause);
        eq("InvalidParameterException(cause) message", ipOnly.getMessage(), cause.toString());

        // Null is permitted and means "unknown", not "absent argument".
        ok("a null cause is allowed",
           new NoSuchElementException("x", null).getCause() == null);
    }

    static void rsaSchluessel() {
        BigInteger modulus = BigInteger.valueOf(3233);
        BigInteger exponent = BigInteger.valueOf(17);
        MGF1ParameterSpec params = MGF1ParameterSpec.SHA256;

        RSAPublicKeySpec pub = new RSAPublicKeySpec(modulus, exponent, params);
        eq("RSAPublicKeySpec modulus", pub.getModulus(), modulus);
        eq("RSAPublicKeySpec exponent", pub.getPublicExponent(), exponent);
        ok("RSAPublicKeySpec params", pub.getParams() == params);

        // The old two-argument form has to keep working and has to report no parameters — this is
        // where delegating the old constructor to the new one could have gone wrong.
        RSAPublicKeySpec plain = new RSAPublicKeySpec(modulus, exponent);
        eq("RSAPublicKeySpec(2) modulus", plain.getModulus(), modulus);
        ok("RSAPublicKeySpec(2) params is null", plain.getParams() == null);

        RSAPrivateKeySpec priv = new RSAPrivateKeySpec(modulus, exponent, params);
        eq("RSAPrivateKeySpec modulus", priv.getModulus(), modulus);
        eq("RSAPrivateKeySpec exponent", priv.getPrivateExponent(), exponent);
        ok("RSAPrivateKeySpec params", priv.getParams() == params);

        RSAPrivateKeySpec privPlain = new RSAPrivateKeySpec(modulus, exponent);
        ok("RSAPrivateKeySpec(2) params is null", privPlain.getParams() == null);
    }

    public static void main(String[] args) {
        System.out.println("Checksum defaults, causal constructors and RSA key parameters on "
                                   + System.getProperty("os.name") + " "
                                   + System.getProperty("os.arch"));

        pruefsummen();
        ausnahmen();
        rsaSchluessel();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
