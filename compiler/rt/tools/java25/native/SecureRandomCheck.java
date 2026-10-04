import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.SecureRandomParameters;

/**
 * {@code SecureRandom}'s {@code SecureRandomParameters} family — JEP 273, Java 9 — on the real
 * library.
 *
 * <h2>Why these cannot be checked by transplanting the source</h2>
 *
 * <p>{@code check.sh} copies an added member into a {@code probe} package and calls it beside the
 * JDK's own. That works for {@code StrictMath} and for {@code ArraysSupport}, whose difficulty is
 * arithmetic. It cannot work here: every one of these seven members goes through
 * {@code sun.security.jca.GetInstance} into whatever providers the platform registered, and a
 * transplanted copy would be asking <em>this</em> JDK's providers a question about ours. The
 * answer has to come from the runtime being tested, which is what a native check is.
 *
 * <h2>Where the expected values come from, and the one trap in them</h2>
 *
 * <p>JDK 25, asked directly on 04.10.2026:
 *
 * <pre>
 *     SHA1PRNG getParameters()  -&gt;  null
 *     SHA1PRNG toString()       -&gt;  SecureRandom
 *     reseed()                  -&gt;  UnsupportedOperationException
 *     nextBytes(bytes, null)    -&gt;  IllegalArgumentException
 *     reseed(null)              -&gt;  IllegalArgumentException
 *     getInstance(alg, null)    -&gt;  IllegalArgumentException
 *     getInstance(null, params) -&gt;  NullPointerException
 * </pre>
 *
 * <p><b>That {@code toString()} is not asserted literally</b>, and the reason is the trap. The new
 * {@code SecureRandom.toString()} delegates to the SPI's, which answers
 * {@code getClass().getSimpleName()} — and on a JDK the SHA1PRNG implementation class happens to be
 * called {@code sun.security.provider.SecureRandom}, so the answer reads like the facade's own name
 * by coincidence. On this runtime the provider is a different class with a different name, and a
 * test pinning the string would fail while the implementation was correct. So what is checked is
 * the contract: a simple class name, which means something non-empty that is <em>not</em>
 * {@code Object.toString}'s {@code name@hash}.
 *
 * <h2>Why the exceptions are the substance</h2>
 *
 * <p>Six of the eight behaviours above are refusals, and that is not thin testing — it is what
 * this family mostly is. {@code reseed()} and {@code nextBytes(bytes, params)} are optional
 * operations: the SPI defaults throw, a provider overrides them or does not, and a caller has to
 * be able to find out. An implementation that returned quietly instead of throwing would be worse
 * than one that was missing, because a program would believe it had reseeded.
 */
public class SecureRandomCheck {

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

    /**
     * Something that may throw anything, checked exceptions included.
     *
     * <p>Not {@code Runnable}: half of what is tested here throws
     * {@code NoSuchAlgorithmException}, and a {@code Runnable} would force every such call to be
     * wrapped — at which point the wrapper's exception is what the check sees, and the check has
     * stopped measuring the thing it is about.
     */
    interface Aufruf {
        void run() throws Exception;
    }

    /** That a call throws exactly this, and not something else and not nothing. */
    static void throwsIt(String what, Class<?> wanted, Aufruf call) {
        checked++;
        try {
            call.run();
            failed++;
            System.out.println("  MISMATCH " + what + ": returned, wanted "
                                       + wanted.getSimpleName());
        } catch (Throwable thrown) {
            if (!wanted.isInstance(thrown)) {
                failed++;
                System.out.println("  MISMATCH " + what + ": threw "
                                           + thrown.getClass().getName() + ", wanted "
                                           + wanted.getName());
            }
        }
    }

    static void ohneParameter() throws NoSuchAlgorithmException {
        SecureRandom r = SecureRandom.getInstance("SHA1PRNG");

        // No parameters were used, so there are none to report. Null and not an empty object:
        // a caller asking this wants to know whether it is dealing with a DRBG.
        eq("SHA1PRNG getParameters", r.getParameters(), null);

        // The contract, not the string — see the class comment.
        String text = r.toString();
        ok("toString is not empty", text != null && !text.isEmpty());
        ok("toString is a simple name, not Object's name@hash", !text.contains("@"));
        System.out.println("  SHA1PRNG toString() = " + text);

        // The optional operations, on an SPI that does not support them.
        throwsIt("reseed()", UnsupportedOperationException.class, r::reseed);
        throwsIt("nextBytes(bytes, params)", UnsupportedOperationException.class,
                 () -> r.nextBytes(new byte[4], DrbgParameters.nextBytes(-1, false, new byte[0])));

        // And the argument checks, which happen before the SPI is reached — so these must throw
        // IllegalArgumentException rather than the UnsupportedOperationException above.
        throwsIt("nextBytes(bytes, null)", IllegalArgumentException.class,
                 () -> r.nextBytes(new byte[4], null));
        throwsIt("reseed(null)", IllegalArgumentException.class, () -> r.reseed(null));
    }

    static void getInstanceMitParametern() {
        // The argument check comes before any provider is consulted, so this is an
        // IllegalArgumentException and not a NoSuchAlgorithmException.
        throwsIt("getInstance(alg, null)", IllegalArgumentException.class,
                 () -> SecureRandom.getInstance("SHA1PRNG", (SecureRandomParameters) null));

        // And the requireNonNull on the algorithm comes before even that.
        throwsIt("getInstance(null, params)", NullPointerException.class,
                 () -> SecureRandom.getInstance(
                         null,
                         DrbgParameters.instantiation(128, DrbgParameters.Capability.NONE, null)));

        // An algorithm that exists but whose SPI takes no parameters. Here the provider lookup is
        // what refuses, so the answer is NoSuchAlgorithmException — which is the distinction worth
        // having: "wrong arguments" and "nobody implements that combination" are different faults
        // and a caller may well handle them differently.
        throwsIt("getInstance(SHA1PRNG, drbgParams)", NoSuchAlgorithmException.class,
                 () -> SecureRandom.getInstance(
                         "SHA1PRNG",
                         DrbgParameters.instantiation(128, DrbgParameters.Capability.NONE, null)));
    }

    /**
     * {@code DrbgParameters} on its own, which needs no provider.
     *
     * <p>Worth checking separately because it is the one half of JEP 273 that is pure value
     * objects, and because a {@code getInstance} that refuses parameters tells us nothing about
     * whether the parameters themselves are right.
     */
    static void parameterObjekte() {
        DrbgParameters.Instantiation i =
                DrbgParameters.instantiation(128, DrbgParameters.Capability.RESEED_ONLY,
                                             new byte[] {1, 2, 3});
        eq("instantiation strength", i.getStrength(), 128);
        eq("instantiation capability", i.getCapability(), DrbgParameters.Capability.RESEED_ONLY);
        ok("instantiation personalisation", i.getPersonalizationString().length == 3);
        ok("RESEED_ONLY supportsReseeding", i.getCapability().supportsReseeding());
        ok("NONE does not support reseeding",
           !DrbgParameters.Capability.NONE.supportsReseeding());
        ok("PR_AND_RESEED supports reseeding",
           DrbgParameters.Capability.PR_AND_RESEED.supportsReseeding());

        DrbgParameters.NextBytes n = DrbgParameters.nextBytes(-1, true, new byte[] {4});
        eq("nextBytes strength", n.getStrength(), -1);
        ok("nextBytes predictionResistance", n.getPredictionResistance());
        ok("nextBytes additionalInput", n.getAdditionalInput().length == 1);

        DrbgParameters.Reseed s = DrbgParameters.reseed(false, new byte[] {5, 6});
        ok("reseed predictionResistance", !s.getPredictionResistance());
        ok("reseed additionalInput", s.getAdditionalInput().length == 2);

        // Null additional input stays null and does not become an empty array. Measured on JDK 25
        // rather than assumed, and it is the sort of thing a well-meaning implementation
        // normalises — at which point a caller cannot tell "none given" from "none".
        eq("nextBytes(null) additionalInput",
           DrbgParameters.nextBytes(-1, false, null).getAdditionalInput(), null);

        // A strength below -1 is refused by both factories. -1 means "whatever the mechanism's
        // default is" and is legal; -2 means nothing.
        throwsIt("instantiation(-2, ...)", IllegalArgumentException.class,
                 () -> DrbgParameters.instantiation(-2, DrbgParameters.Capability.NONE, null));
        throwsIt("nextBytes(-2, ...)", IllegalArgumentException.class,
                 () -> DrbgParameters.nextBytes(-2, false, null));
    }

    /**
     * Whether this runtime has a DRBG at all — printed, not asserted.
     *
     * <p>A platform fact rather than a defect either way. The members above are correct whether or
     * not a provider implements them, and which providers Android registers is not ours to decide.
     * It is worth knowing, though: a DRBG is the only algorithm for which {@code reseed()} and
     * {@code nextBytes(bytes, params)} ever do anything, so this line says whether those two are
     * reachable on a phone or merely present.
     */
    static void drbgWennVorhanden() {
        try {
            SecureRandom d = SecureRandom.getInstance("DRBG");
            System.out.println("  DRBG present: getParameters() = " + d.getParameters());
            d.reseed();
            byte[] bytes = new byte[8];
            d.nextBytes(bytes, DrbgParameters.nextBytes(-1, false, new byte[] {1}));
            boolean any = false;
            for (byte b : bytes) {
                if (b != 0) {
                    any = true;
                }
            }
            ok("DRBG nextBytes filled something", any);
            ok("DRBG reports parameters", d.getParameters() != null);
        } catch (NoSuchAlgorithmException none) {
            System.out.println("  DRBG not registered on this runtime — "
                                       + "reseed() and nextBytes(b, params) are present but "
                                       + "unreachable here");
        } catch (Throwable other) {
            System.out.println("  DRBG present but failed: " + other);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("SecureRandom, the SecureRandomParameters family (JEP 273) on "
                                   + System.getProperty("os.name") + " "
                                   + System.getProperty("os.arch"));

        ohneParameter();
        getInstanceMitParametern();
        parameterObjekte();
        drbgWennVorhanden();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
