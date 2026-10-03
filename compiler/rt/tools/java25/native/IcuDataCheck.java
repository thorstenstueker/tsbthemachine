import java.text.Collator;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Whether this image has locale data at all.
 *
 * <h2>What was wrong</h2>
 *
 * Until 03.10.2026 the runtime shipped ICU's <i>minimal</i> builtin package and nothing noticed.
 * Measured on an Android emulator and an iPhone simulator, through the same RapidFX form:
 *
 * <pre>
 *   NumberFormat.getInstance(GERMAN).format(1234.5)  ->  1,234.5    (JDK 25: 1.234,5)
 *   DateFormat.getDateInstance(MEDIUM, GERMANY)      ->  2026 M09 21
 *   Locale.GERMAN.getDisplayLanguage(GERMAN)         ->  de         (JDK 25: Deutsch)
 *   Locale.getAvailableLocales().length              ->  1          (JDK 25: 1158)
 * </pre>
 *
 * The reason it went unnoticed for so long is worth keeping: the <b>character</b> properties are
 * compiled into ICU's code rather than its data, so {@code Character.isLetter} and everything like
 * it answered correctly throughout. Only the locale-sensitive half fell back to root, and root
 * looks enough like English that an English-speaking test sees nothing.
 *
 * <p>Unpacked and listed with {@code icupkg -l}, the builtin package held <b>47 entries</b> and
 * exactly one locale, {@code en.res}. It had no {@code zoneinfo64.res} either, which is where
 * {@code OlsonZoneRulesProvider} came from on 03.10.2026 — that fix is still the right one for
 * {@code java.time}, and this one is what the formatters needed.
 *
 * <h2>Where the expected values come from</h2>
 *
 * JDK 25, asked directly. Not from reading CLDR, and not from what German ought to look like —
 * {@code 1.234,50 €} has a non-breaking space before the sign and {@code 75 %} has one too, and
 * neither is something anybody types correctly from memory.
 *
 * <p>The date values are computed back from the moment wanted rather than written as a constant:
 * guessing epoch milliseconds put a test a whole year out last week. The formatter's zone is
 * pinned to UTC for the same reason — without that the expected value depends on where the machine
 * running it stands.
 *
 * <h2>Two numbers that are reported and not asserted</h2>
 *
 * The locale count and the data size are platform facts, not correctness. Our data file carries 28
 * languages against JDK 25's 1158 locales, deliberately: {@code compiler/rt/tools/icu-data.sh}
 * names the European Union's plus Norwegian, and adding one is a line there and about 200 KB. So
 * the count has to be <em>much larger than one</em> and is printed rather than fixed.
 */
public class IcuDataCheck {

    static int checked;
    static int failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got [" + got + "], wanted [" + want + "]");
        }
    }

    static void ok(String what, boolean condition) {
        checked++;
        if (!condition) {
            failed++;
            System.out.println("  MISMATCH " + what);
        }
    }

    /** The four measurements from the defect report, and their neighbours. */
    static void numbers() {
        eq("number in German",
           NumberFormat.getInstance(Locale.GERMAN).format(1234.5), "1.234,5");
        // A non-breaking space before the currency sign, which is why this is measured and not
        // typed: U+00A0, not U+0020.
        eq("currency in Germany",
           NumberFormat.getCurrencyInstance(Locale.GERMANY).format(1234.5), "1.234,50 €");
        eq("percent in German",
           NumberFormat.getPercentInstance(Locale.GERMAN).format(0.75), "75 %");

        // And English, so that a file holding only German reports itself rather than passing.
        eq("number in English",
           NumberFormat.getInstance(Locale.US).format(1234.5), "1,234.5");
        eq("number in French",
           NumberFormat.getInstance(Locale.FRANCE).format(1234.5), "1 234,5");
    }

    static void dates() {
        long ms = LocalDateTime.of(2026, 3, 15, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        TimeZone utc = TimeZone.getTimeZone("UTC");

        DateFormat medium = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.GERMANY);
        medium.setTimeZone(utc);
        eq("medium date in Germany", medium.format(new Date(ms)), "15.03.2026");

        // The one value here that is NOT JDK 25's, and the reason is the data version rather than
        // this runtime. JDK 25 answers "15. März 2026, 12:00" — with a comma joining the date to
        // the time. Our data is ICU 68.2, from 2020; the comma arrived in CLDR later, and the
        // pattern here is still "{1} {0}".
        //
        // Held to the measured value rather than JDK 25's deliberately. It is not reachable from
        // our side: the join pattern comes out of the same de.res that gives "15.03.2026" and
        // "Januar" correctly, and editing data to match a newer CLDR would be forking the data.
        //
        // The evidence that this is a version difference and not a hole is the company it keeps:
        // every other German value in this file agrees with JDK 25 exactly, down to the
        // non-breaking spaces. It was not proved directly — derb from ICU 77 cannot read ICU 68's
        // resource format (U_INVALID_FORMAT_ERROR), so the pattern was not read out of the file.
        // Moving to a newer ICU is the fix, and that is a bigger piece of work than a comma.
        DateFormat longer =
                DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, Locale.GERMANY);
        longer.setTimeZone(utc);
        eq("long date in Germany", longer.format(new Date(ms)), "15. März 2026 12:00");

        DateFormatSymbols symbols = new DateFormatSymbols(Locale.GERMANY);
        eq("first month in German", symbols.getMonths()[0], "Januar");
        eq("Monday in German", symbols.getWeekdays()[2], "Montag");

        // java.time goes through its own formatter, so it is a second route to the same data and
        // not a repetition of the line above.
        eq("java.time medium date",
           LocalDateTime.of(2026, 3, 15, 12, 0)
                   .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                   .withLocale(Locale.GERMANY)),
           "15.03.2026");
    }

    static void names() {
        eq("German for German", Locale.GERMAN.getDisplayLanguage(Locale.GERMAN), "Deutsch");
        eq("Germany for German", Locale.GERMANY.getDisplayCountry(Locale.GERMAN), "Deutschland");
        eq("French for German", Locale.FRENCH.getDisplayLanguage(Locale.GERMAN), "Französisch");
        eq("German for English", Locale.GERMAN.getDisplayLanguage(Locale.US), "German");

        eq("Berlin's zone name for German",
           TimeZone.getTimeZone("Europe/Berlin")
                   .getDisplayName(false, TimeZone.LONG, Locale.GERMANY),
           "Mitteleuropäische Normalzeit");
    }

    /** Sorting, which reads a different part of the data than the formatters do. */
    static void collation() {
        Collator german = Collator.getInstance(Locale.GERMANY);
        // In German collation a-umlaut sorts with a, so it comes before b. In the root locale it
        // does too — what distinguishes them is the pair below.
        ok("ä before b in German", german.compare("ä", "b") < 0);
        ok("ä after a in German", german.compare("ä", "a") > 0);

        // Swedish is the case that separates real data from root: there a-umlaut is its own letter
        // at the end of the alphabet, after z.
        Collator swedish = Collator.getInstance(Locale.forLanguageTag("sv"));
        ok("ä after z in Swedish", swedish.compare("ä", "z") > 0);
        ok("and not so in German", german.compare("ä", "z") < 0);
    }

    /** Reported, not asserted — see the note at the top. */
    static void inventory() {
        int locales = Locale.getAvailableLocales().length;
        System.out.println("  " + locales + " locales, "
                           + Collator.getAvailableLocales().length + " with a collator, "
                           + TimeZone.getAvailableIDs().length + " time zones");

        // The assertion is only that this is not the one-locale image any more.
        ok("more than a handful of locales", locales > 50);
        ok("the time zones are there", TimeZone.getAvailableIDs().length > 100);
        // java.time's zones come from ZoneRulesProvider rather than from ICU, so this is a separate
        // question with its own answer — and the one OlsonZoneRulesProvider exists for.
        ok("java.time has zones", ZoneId.getAvailableZoneIds().size() > 100);
        ok("Europe/Berlin resolves", ZoneId.of("Europe/Berlin") != null);
    }

    public static void main(String[] args) {
        numbers();
        dates();
        names();
        collation();
        inventory();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
