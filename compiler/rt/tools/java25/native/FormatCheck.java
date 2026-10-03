import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What locale-sensitive formatting actually does on this runtime.
 *
 * <p>Written to settle one observation and it reports rather than asserts, because nobody yet
 * knows which of the numbers below is the library's intention. Measured 03.10.2026 on both an
 * Android emulator and an iPhone simulator through a RapidFX form:
 * {@code NumberFormat.getInstance(Locale.GERMAN).format(1234.5)} answered {@code 1,234.5} — the
 * English grouping — where JDK 25 answers {@code 1.234,5}.
 *
 * <p>That is the sort of thing an ERP notices on its first invoice and nothing else notices at
 * all. It is the same on both telephones, so it is not the iOS ICU4J gap that
 * {@code OlsonZoneRulesProvider} exists for; it is this runtime's locale data.
 *
 * <h2>The cause, found after this was written</h2>
 *
 * <p>{@code compiler/rt/android/cmake/icu/CMakeLists.txt} links
 * {@code icu4c/source/stubdata/stubdata.cpp} — ICU's <b>placeholder</b>, which carries no data at
 * all. The real {@code icudt*.dat} is not in the tree and is not linked, so ICU runs on whatever
 * its code has built in. That is enough for the character properties, which is why
 * {@code Character.isAlphabetic} and the emoji predicates work, and it is nothing at all for
 * anything locale-sensitive.
 *
 * <p>Hence {@code available locales: 1}. Everything falls back to root: German numbers come out
 * English, a German date comes out as {@code 2026 M09 21}, and {@code Locale.GERMAN
 * .getDisplayLanguage(GERMAN)} answers {@code de} rather than {@code Deutsch}.
 *
 * <p>So: printed with the JDK's answer beside it, and left for a decision rather than turned into
 * a failing check. A check that fails on every run is one nobody reads — and the decision is a
 * product one, because the full data file is tens of megabytes in every application.
 */
public class FormatCheck {

    static void line(String what, String got, String jdk25) {
        System.out.println("  " + what + ": " + got
                           + (got.equals(jdk25) ? "  (agrees with JDK 25)"
                                                : "  <- JDK 25 says " + jdk25));
    }

    public static void main(String[] args) {
        line("number de", NumberFormat.getInstance(Locale.GERMAN).format(1234.5), "1.234,5");
        line("number de_DE", NumberFormat.getInstance(Locale.GERMANY).format(1234.5), "1.234,5");
        line("number en_US", NumberFormat.getInstance(Locale.US).format(1234.5), "1,234.5");
        line("number fr_FR", NumberFormat.getInstance(Locale.FRANCE).format(1234.5), "1 234,5");
        line("date de_DE",
             DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.GERMANY)
                     .format(new Date(1790000000000L)), "21.09.2026");
        line("displayName de", Locale.GERMAN.getDisplayLanguage(Locale.GERMAN), "Deutsch");
        System.out.println("  available locales: " + Locale.getAvailableLocales().length
                           + "  (JDK 25 has 1158)");

        // Straight at ICU, to tell "no data" from "data that nothing reads". getIcuVersion is
        // compiled into the library and answers either way; getCldrVersion has to be read out of
        // the data file, so it is the one that distinguishes them.
        try {
            Class<?> icu = Class.forName("libcore.icu.ICU");
            Object version = icu.getMethod("getIcuVersion").invoke(null);
            Object cldr = icu.getMethod("getCldrVersion").invoke(null);
            Object unicode = icu.getMethod("getUnicodeVersion").invoke(null);
            System.out.println("  ICU4C: version=" + version + " cldr=" + cldr
                               + " unicode=" + unicode);

            // The raw list, before libcore's ICU class filters or caches anything. If this is long
            // and Locale.getAvailableLocales() is short, the loss is on the Java side.
            java.lang.reflect.Method raw = icu.getDeclaredMethod("getAvailableLocalesNative");
            raw.setAccessible(true);
            String[] names = (String[]) raw.invoke(null);
            System.out.println("  ICU4C locales: " + (names == null ? "null" : names.length)
                               + (names != null && names.length > 3
                                  ? "  first: " + names[0] + " " + names[1] + " " + names[2] : ""));
        } catch (Throwable noIcu) {
            System.out.println("  ICU4C: unreadable — " + noIcu.getCause());
        }
        System.out.println("0 checked, 0 failed");
    }
}
