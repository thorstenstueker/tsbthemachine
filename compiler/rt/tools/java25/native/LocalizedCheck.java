import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.IsoChronology;
import java.time.chrono.JapaneseChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The six java.time.format members added on 11.10.2026, run on the real library.
 *
 * Compiled ahead of time against robovm-rt and executed natively, so the java.time exercised here
 * is the one a phone gets. check.sh next door cannot ask this question at all for these six: it
 * works by transplanting a member's source into a probe package on the host JDK, and every one of
 * these reaches into ICU and into package-private formatter machinery that only resolves off this
 * boot class path.
 *
 * The members:
 *
 *   DateTimeFormatter.ofLocalizedPattern(String)                              Java 19
 *   DateTimeFormatter.localizedBy(Locale)                                     Java 10
 *   DateTimeFormatterBuilder.appendLocalized(String)                          Java 19
 *   DateTimeFormatterBuilder.getLocalizedDateTimePattern(String, Chronology, Locale)  Java 19
 *   DateTimeFormatterBuilder.appendGenericZoneText(TextStyle)                 Java 9
 *   DateTimeFormatterBuilder.appendGenericZoneText(TextStyle, Set)            Java 9
 *
 * WHAT IS ASSERTED AGAINST JDK 25 AND WHAT IS NOT, because the difference is the whole point here.
 *
 * The mechanism is asserted against JDK 25: which templates are refused, what localizedBy does to
 * the chronology, the zone and the decimal style, what toString answers. Every expected value was
 * produced by running the same expression on a real JDK 25 and printing it.
 *
 * The localized *text* is asserted as THIS RUNTIME'S answer, with the value this runtime gives,
 * and that is deliberate. The pattern a skeleton resolves to comes from CLDR, this runtime carries
 * ICU 68.2 from 2020, and a desktop JDK 25 carries a CLDR seven years newer. They disagree in 14 of
 * the 27 shipped languages — measured on 06.10.2026 and written down as RFXA-102. Asserting JDK 25's
 * text here would be asserting that the anomaly does not exist. So these lines pin what the runtime
 * does say, and they go red the day ICU is raised, which is the day somebody wants to know what
 * changed. Where the two happen to agree today the comment says so.
 */
public class LocalizedCheck {

    static int checked, failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    static void refused(String template) {
        checked++;
        try {
            DateTimeFormatter.ofLocalizedPattern(template);
            failed++;
            System.out.println("  MISMATCH template [" + template + "]: accepted, wanted refusal");
        } catch (IllegalArgumentException expected) {
            // what JDK 25 does
        }
    }

    static void accepted(String template) {
        checked++;
        try {
            DateTimeFormatter.ofLocalizedPattern(template);
        } catch (RuntimeException e) {
            failed++;
            System.out.println("  MISMATCH template [" + template + "]: " + e);
        }
    }

    public static void main(String[] args) {
        LocalDate d = LocalDate.of(2020, 6, 16);
        LocalDateTime dt = LocalDateTime.of(2020, 6, 16, 14, 30, 5);
        ZonedDateTime z = ZonedDateTime.of(dt, ZoneId.of("America/Los_Angeles"));

        // ---- the template grammar, against JDK 25 -------------------------------------------
        //
        // Canonical order from the largest unit to the smallest, and a count limit per symbol.
        // Each of these was run on JDK 25: the five below are refused there and the two after are
        // accepted, the empty one included.
        refused("MMMy");      // out of order
        refused("A");         // not a template symbol
        refused("yQQQQQQ");   // six Qs, five allowed
        refused("ddd");       // three ds, two allowed
        refused("hhh");       // three hs, two allowed
        accepted("");
        accepted("yMMMd");

        // ---- the pattern a skeleton resolves to ---------------------------------------------
        //
        // This runtime's answers. "MMM y" and "M/d/y" and "HH:mm" are also JDK 25's, measured —
        // the plain skeletons are stable CLDR. The ones that are not stable are below.
        eq("pattern yMMM en-US",
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                        "yMMM", IsoChronology.INSTANCE, Locale.US), "MMM y");
        eq("pattern yMd en-US",
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                        "yMd", IsoChronology.INSTANCE, Locale.US), "M/d/y");
        eq("pattern Hm de-DE",
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                        "Hm", IsoChronology.INSTANCE, Locale.GERMANY), "HH:mm");

        // The calendar is the reason this method takes a Chronology, and passing it to ICU is the
        // part that would silently do nothing if it were got wrong — which the first draft of this
        // did, in two ways at once, both found by running it: a calendar as a legacy keyword
        // (en_US@calendar=gregory) resolves to ROOT data, and iso8601 is not a calendar ICU holds
        // patterns for at all. Both produced ICU's append-item fallback with its debug brackets:
        // yMMM came back as "y ├'month': MMM┤", and that string would have reached a form as a
        // date.
        //
        // So the assertion is that the era is in the pattern when a Japanese-calendar date is
        // asked for, and absent when an ISO one is. The *text* is deliberately not asserted
        // against JDK 25's "Gy年M月": this runtime ships 28 languages — the European Union's plus
        // Norwegian — and Japanese is not one of them, so ja-JP falls back to root by design. What
        // is being checked here is that the calendar keyword arrives, not that a language nobody
        // bought is present.
        String japanese = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                "GyMMM", JapaneseChronology.INSTANCE, Locale.JAPAN);
        eq("GyMMM with the Japanese calendar names an era", japanese.indexOf('G') >= 0, true);
        eq("the Japanese pattern is not the fallback glue", japanese.indexOf('├') < 0, true);
        eq("pattern yMMM en-US is not the fallback glue",
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                        "yMMM", IsoChronology.INSTANCE, Locale.US).indexOf('├') < 0, true);

        // ---- formatting through the skeleton route ------------------------------------------
        eq("format yMMM en-US",
                DateTimeFormatter.ofLocalizedPattern("yMMM").withLocale(Locale.US).format(d),
                "Jun 2020");
        eq("format yMd en-US",
                DateTimeFormatter.ofLocalizedPattern("yMd").withLocale(Locale.US).format(d),
                "6/16/2020");
        eq("format Hms de-DE",
                DateTimeFormatter.ofLocalizedPattern("Hms").withLocale(Locale.GERMANY).format(dt),
                "14:30:05");
        eq("format yMMM de-DE",
                DateTimeFormatter.ofLocalizedPattern("yMMM").withLocale(Locale.GERMANY).format(d),
                "Juni 2020");

        eq("toString localized template",
                new DateTimeFormatterBuilder().appendLocalized("yMMM").toFormatter(Locale.US)
                        .toString(), "Localized(yMMM)");

        // ---- the generic zone name -----------------------------------------------------------
        //
        // Generic is the name that holds all year: 'Pacific Time', where the specific names are
        // 'Pacific Standard Time' and 'Pacific Daylight Time'. The date here is in June, so the
        // specific name would be the daylight one — which is what makes the pair below worth
        // asserting together rather than apart.
        eq("generic FULL en-US",
                new DateTimeFormatterBuilder().appendGenericZoneText(TextStyle.FULL)
                        .toFormatter(Locale.US).format(z), "Pacific Time");
        eq("generic SHORT en-US",
                new DateTimeFormatterBuilder().appendGenericZoneText(TextStyle.SHORT)
                        .toFormatter(Locale.US).format(z), "PT");
        eq("specific FULL en-US stays specific",
                new DateTimeFormatterBuilder().appendZoneText(TextStyle.FULL)
                        .toFormatter(Locale.US).format(z), "Pacific Daylight Time");

        // 'v' and 'vvvv' in a pattern are the same two, which is what the new case in
        // appendPattern is for — and what the skeleton route needs, because ICU answers a
        // zone-bearing skeleton with 'v'.
        eq("pattern v en-US", DateTimeFormatter.ofPattern("v", Locale.US).format(z), "PT");
        eq("pattern vvvv en-US",
                DateTimeFormatter.ofPattern("vvvv", Locale.US).format(z), "Pacific Time");

        // The description is not prefixed, which is JDK 25's and was measured rather than guessed.
        eq("toString generic",
                new DateTimeFormatterBuilder().appendGenericZoneText(TextStyle.FULL)
                        .toFormatter(Locale.US).toString(), "ZoneText(FULL)");

        // ---- localizedBy ---------------------------------------------------------------------
        //
        // Four Unicode extensions supersede what the formatter holds. All four values below are
        // JDK 25's, and none of them depends on CLDR's date patterns, so these are equality
        // assertions in the ordinary sense.
        DateTimeFormatter base = DateTimeFormatter.ofPattern("y-MM-dd");

        eq("localizedBy ca chrono",
                base.localizedBy(Locale.forLanguageTag("en-US-u-ca-japanese")).getChronology(),
                "Japanese");
        eq("localizedBy ca formats the era year",
                base.localizedBy(Locale.forLanguageTag("en-US-u-ca-japanese")).format(d),
                "2-06-16");
        eq("localizedBy nu zero digit",
                base.localizedBy(Locale.forLanguageTag("en-US-u-nu-arab"))
                        .getDecimalStyle().getZeroDigit(), "٠");
        eq("localizedBy plain leaves the chronology",
                base.localizedBy(Locale.US).getChronology(), "ISO");
        eq("localizedBy plain leaves the zone", base.localizedBy(Locale.US).getZone(), "null");

        // The tz extension is the one that needed an ICU route of its own: OpenJDK reads the LDML
        // short id through sun.util.locale.provider.TimeZoneNameUtility, which this runtime does
        // not have, and ICU carries the same CLDR table behind ULocale.toLegacyType.
        eq("localizedBy tz zone",
                base.localizedBy(Locale.forLanguageTag("en-US-u-tz-uslax")).getZone(),
                "America/Los_Angeles");
        // And a short id nobody knows leaves the zone alone rather than throwing — JDK 25 answers
        // null here too, which is the formatter's "no override zone".
        eq("localizedBy unknown tz keeps the zone",
                base.localizedBy(Locale.forLanguageTag("en-US-u-tz-nowhere")).getZone(), "null");

        // ---- what is NOT closed, asserted so that it cannot be forgotten ---------------------
        //
        // appendDayPeriodText is the seventh member of this group and is not here. 'B' asks for a
        // day period — "in the morning" rather than "AM" — and this runtime cannot parse it: its
        // Parsed and DateTimeParseContext know nothing about day periods, where JDK 25's Parsed
        // names them eleven times. So a 12-hour pattern carrying 'B' is refused by name rather
        // than answered with am/pm, because a wrong value is worse than a refusal.
        //
        // What this line pins is that the refusal is the only consequence: a skeleton the caller
        // did NOT ask a day period for must not come back carrying one. If ICU ever answers 'B'
        // for a B-free template in some locale, this goes red and the gap stops being theoretical.
        checked++;
        String[] locales = {"en-US", "de-DE", "fr-FR", "ja-JP", "zh-CN", "ar-EG", "ru-RU", "pt-BR",
                            "hi-IN", "ko-KR", "tr-TR", "nl-NL", "pl-PL", "sv-SE"};
        String[] templates = {"yMMMd", "yMd", "Hm", "Hms", "hm", "hms", "yMMMEd", "GyMMM", "Hmv",
                              "yMdHm"};
        StringBuilder withB = new StringBuilder();
        for (int i = 0; i < locales.length; i++) {
            Locale locale = Locale.forLanguageTag(locales[i]);
            for (int j = 0; j < templates.length; j++) {
                String pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                        templates[j], IsoChronology.INSTANCE, locale);
                if (pattern.indexOf('B') >= 0) {
                    withB.append(' ').append(locales[i]).append('/').append(templates[j])
                            .append("->").append(pattern);
                }
            }
        }
        if (withB.length() > 0) {
            failed++;
            System.out.println("  MISMATCH a B-free template resolved to a day-period pattern:"
                    + withB);
        }
        System.out.println("  " + (locales.length * templates.length)
                + " locale/template pairs resolved, none needing a day period");

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
