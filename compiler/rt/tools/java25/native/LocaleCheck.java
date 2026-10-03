import java.io.Console;
import java.nio.charset.Charset;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Set;

/**
 * Locale's and Console's Java 9-to-23 additions, on the real library.
 *
 * <h2>What is worth pinning</h2>
 *
 * <b>The obsolete ISO 639 codes go the other way here than in JDK 25, and that is not this
 * change's doing.</b> Measured 03.10.2026, every route:
 *
 * <pre>
 *                            this library   JDK 25
 *   new Locale("he")              iw          he
 *   Locale.of("he")               iw          he
 *   forLanguageTag("he")          iw          he
 *   forLanguageTag("iw")          iw          he
 *   Builder.setLanguage("he")     iw          he
 *   new Locale("he").toLanguageTag()
 *                                 he          he
 * </pre>
 *
 * Hebrew, Yiddish and Indonesian changed ISO code in 1989 and Java stored the old one — "iw", "ji",
 * "in" — for compatibility. OpenJDK has since turned that round and normalises to the modern code
 * everywhere; this fork still stores the old one, in {@code BaseLocale.getInstance}, which every
 * route goes through.
 *
 * <p>So {@code of} is held to the fork's behaviour here rather than to JDK 25's, deliberately.
 * Making the factory alone modern would leave {@code Locale.of("he").equals(new Locale("he"))}
 * false, which is true nowhere and would be a trap of our own making. Turning the whole conversion
 * round is a separate piece of work: it reaches serialisation, {@code toString} and every stored
 * locale, and it is written up under <i>Known faults</i> in RapidFX's {@code docs/OPEN.md}.
 *
 * <b>{@code of} hands back a shared instance.</b> The constructor makes a new object every time;
 * the factory goes through the cache. {@code ==} is therefore true for two calls with the same
 * arguments, and a test that only checked {@code equals} would pass over a factory that forgot the
 * cache and allocated per call.
 *
 * <b>The three ISO 3166 parts have different sources here.</b> PART1_ALPHA2 comes from ICU in this
 * fork, where OpenJDK reads its own table — so its size is a platform number and is not compared.
 * PART1_ALPHA3 and PART3 come from {@code LocaleISOData}, which is byte-identical with JDK 25's, so
 * those two are compared exactly: 249 and 31.
 *
 * <b>Case folding depends on what came before.</b> {@code x-Latn-foo} folds to {@code x-latn-foo}
 * and not to {@code x-Latn-foo}: after the private-use prefix every subtag is lowercase whatever it
 * looks like, and {@code Latn} only looks like a script. The same holds after an extension
 * singleton. Three of the cases below exist for that rule alone.
 *
 * <b>A legacy tag folds to its registered spelling and not by rule.</b> {@code i-klingon} and
 * {@code art-lojban} come out of the table unchanged.
 *
 * <h2>Where the expected values come from</h2>
 *
 * JDK 25, asked directly — not from reading the specification. Which is how the ISO 639 difference
 * above was found at all: the first run of this check had {@code of("he")} expecting {@code "he"},
 * because that is what JDK 25 answers, and three assertions failed. Asking the JDK about every
 * route then showed that the difference is not in {@code of} but underneath all of them.
 */
public class LocaleCheck {

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

    static void illformed(String tag) {
        checked++;
        try {
            String got = Locale.caseFoldLanguageTag(tag);
            failed++;
            System.out.println("  MISMATCH caseFold(\"" + tag + "\"): got " + got
                               + ", wanted IllformedLocaleException");
        } catch (IllformedLocaleException expected) {
            // what JDK 25 does
        }
    }

    static void folds(String tag, String want) {
        checked++;
        String got;
        try {
            got = Locale.caseFoldLanguageTag(tag);
        } catch (Throwable threw) {
            got = threw.getClass().getSimpleName();
        }
        if (!got.equals(want)) {
            failed++;
            System.out.println("  MISMATCH caseFold(\"" + tag + "\"): got " + got
                               + ", wanted " + want);
        }
    }

    // --- the factories --------------------------------------------------------------------

    static void factories() {
        eq("of(de)", Locale.of("de"), "de");
        eq("of(de,DE)", Locale.of("de", "DE"), "de_DE");
        eq("of(de,DE,POSIX)", Locale.of("de", "DE", "POSIX"), "de_DE_POSIX");

        // The country is upper-cased and the language lower-cased, as the constructor does.
        eq("of normalises case", Locale.of("DE", "de"), "de_DE");

        // The obsolete ISO 639 codes, and this is where the fork and JDK 25 part company —
        // measured, not assumed, and bigger than these three lines. See the note at the top.
        eq("of(he)", Locale.of("he").getLanguage(), "iw");
        eq("new Locale(he)", new Locale("he").getLanguage(), "iw");
        eq("of(yi)", Locale.of("yi").getLanguage(), "ji");
        eq("of(id)", Locale.of("id").getLanguage(), "in");
        eq("forLanguageTag(he)", Locale.forLanguageTag("he").getLanguage(), "iw");
        eq("forLanguageTag(iw)", Locale.forLanguageTag("iw").getLanguage(), "iw");
        eq("Builder.setLanguage(he)",
           new Locale.Builder().setLanguage("he").build().getLanguage(), "iw");
        // And the limit of the difference, which is worth as much as the difference itself:
        // toLanguageTag answers "he" either way. BCP 47 requires the modern code, so the tag is
        // converted on the way out even though the field holds the old one. A form that talks to
        // a server in language tags therefore sees no difference at all — only one reading
        // getLanguage() does.
        eq("toLanguageTag of he", new Locale("he").toLanguageTag(), "he");
        eq("toLanguageTag of of(he)", Locale.of("he").toLanguageTag(), "he");

        // What matters for the factory itself: it agrees with the constructor. The two differ in
        // JDK 25 and must not differ here, because here neither of them converts the other way.
        ok("of and the constructor agree on he",
           Locale.of("he").equals(new Locale("he")));

        // Shared, where the constructor allocates.
        ok("of returns the cached instance", Locale.of("de", "DE") == Locale.of("de", "DE"));
        ok("of equals what the constructor builds",
           Locale.of("de", "DE").equals(new Locale("de", "DE")));

        try {
            Locale.of(null);
            checked++;
            failed++;
            System.out.println("  MISMATCH of(null): threw nothing, wanted NullPointerException");
        } catch (NullPointerException expected) {
            checked++;
        }
    }

    // --- the streams and sets -------------------------------------------------------------

    static void listings() {
        // Not a fixed number — ICU decides how many there are — but it has to agree with the array
        // it is taken from, and it has to be a snapshot rather than an empty stream.
        long streamed = Locale.availableLocales().count();
        eq("availableLocales agrees with getAvailableLocales",
           streamed, (long) Locale.getAvailableLocales().length);
        ok("availableLocales is not empty", streamed > 0);

        Set<String> alpha3 = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3);
        Set<String> part3 = Locale.getISOCountries(Locale.IsoCountryCode.PART3);
        Set<String> alpha2 = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

        // These two come out of LocaleISOData, which is identical with JDK 25's, so the numbers
        // are exact rather than approximate.
        eq("PART1_ALPHA3 size", alpha3.size(), 249);
        eq("PART3 size", part3.size(), 31);
        ok("PART1_ALPHA3 holds DEU", alpha3.contains("DEU"));
        ok("PART3 holds DDDE", part3.contains("DDDE"));

        // PART1_ALPHA2 is ICU's list here and OpenJDK's table there, so only its shape is checked.
        ok("PART1_ALPHA2 is not empty", !alpha2.isEmpty());
        ok("PART1_ALPHA2 holds DE", alpha2.contains("DE"));
        ok("PART1_ALPHA2 agrees with getISOCountries()",
           alpha2.size() == Locale.getISOCountries().length);

        // The same set object comes back, because it is computed once and cached.
        ok("the set is cached",
           Locale.getISOCountries(Locale.IsoCountryCode.PART3) == part3);

        try {
            Locale.getISOCountries(null);
            checked++;
            failed++;
            System.out.println("  MISMATCH getISOCountries(null): threw nothing");
        } catch (NullPointerException expected) {
            checked++;
        }
    }

    // --- case folding ---------------------------------------------------------------------

    static void caseFolding() {
        folds("en-us", "en-US");
        folds("EN-US", "en-US");
        folds("zh-hant-tw", "zh-Hant-TW");
        folds("ZH-HANT-TW", "zh-Hant-TW");
        folds("sr-Latn-RS", "sr-Latn-RS");
        folds("und-Latn-DE", "und-Latn-DE");

        // After a singleton, case is left alone and lowered — the extension's own value is not a
        // region however much "jp" looks like one.
        folds("ja-jp-u-ca-japanese", "ja-JP-u-ca-japanese");
        folds("th-th-u-nu-thai", "th-TH-u-nu-thai");
        folds("en-a-bbb-x-a-ccc", "en-a-bbb-x-a-ccc");

        // Private use: everything after x- is lowercase, Latn included.
        folds("x-Latn-foo", "x-latn-foo");
        folds("de-de-x-lvariant-POSIX", "de-DE-x-lvariant-POSIX");

        // Legacy tags answer from the table, not by rule.
        folds("i-klingon", "i-klingon");
        folds("art-lojban", "art-lojban");

        illformed("a");
        illformed("en-");
        illformed("-en");
        illformed("en--us");
        illformed("123456789");

        try {
            Locale.caseFoldLanguageTag(null);
            checked++;
            failed++;
            System.out.println("  MISMATCH caseFold(null): threw nothing");
        } catch (NullPointerException expected) {
            checked++;
        }
    }

    // --- Console --------------------------------------------------------------------------

    /**
     * Console's six locale-aware and terminal-aware additions.
     *
     * <p>Every one of them is unreachable on a telephone: {@code System.console()} answers null
     * there and always will, because an application has no controlling terminal. That is not a
     * reason to leave them out and it is the whole argument for full parity — a library that
     * <em>mentions</em> {@code Console.charset()} has to link, even inside a branch no phone ever
     * takes. A missing member is not a run-time surprise here; it is a build that fails on code
     * that would never have run.
     *
     * <p>So what is checked is what can be: that the methods exist with the right signatures, that
     * a null console is what this platform has, and that the delegation is the way round it should
     * be. The reflection is deliberate — calling them would need a terminal.
     */
    static void console() throws Exception {
        Console c = System.console();
        // Not an assertion about the methods: a statement about where this runs. If a console ever
        // appears here, the line below is what says so.
        ok("no console on this platform, as expected", c == null);

        eq("charset() is declared",
           Console.class.getMethod("charset").getReturnType().getName(),
           Charset.class.getName());
        eq("isTerminal() is declared",
           Console.class.getMethod("isTerminal").getReturnType().getName(), "boolean");
        eq("format(Locale,...) returns a Console",
           Console.class.getMethod("format", Locale.class, String.class, Object[].class)
                   .getReturnType().getName(), Console.class.getName());
        eq("printf(Locale,...) returns a Console",
           Console.class.getMethod("printf", Locale.class, String.class, Object[].class)
                   .getReturnType().getName(), Console.class.getName());
        eq("readLine(Locale,...) returns a String",
           Console.class.getMethod("readLine", Locale.class, String.class, Object[].class)
                   .getReturnType().getName(), String.class.getName());
        eq("readPassword(Locale,...) returns a char[]",
           Console.class.getMethod("readPassword", Locale.class, String.class, Object[].class)
                   .getReturnType().getName(), "[C");
    }

    public static void main(String[] args) throws Exception {
        factories();
        listings();
        caseFolding();
        console();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
