package probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Holds {@code TrailingPosixRule} against the two things that can judge it.
 *
 * <p><b>Against the file itself</b>, which is the assertion that matters. A compiled time-zone file
 * states its transitions explicitly up to a cut-off and then states the rule in one line of POSIX
 * text. The two halves describe the same thing on either side of the cut-off, so the rule, applied
 * to a year the file still has transitions for, must land on transitions that file states — to the
 * second, with the same offsets either side. That needs no outside reference and no agreement about
 * what the right answer is: the file is the authority and it checks itself. It is also what caught
 * Gaza, where the explicit data says more than a POSIX string can; see the comment at the
 * comparison.
 *
 * <p><b>Against the host JDK</b>, which compiles the same rules out of the tzdb sources instead of
 * reading them out of a file. Where the two disagree it is worth knowing why, and the answer is
 * usually that the host's files are a newer tzdb than the JDK's own copy — on the machine this was
 * written on, {@code /usr/share/zoneinfo} was 2026c and the JDK's tzdb was older, which is exactly
 * the situation on a phone. So a disagreement here is reported and is only tolerated where the
 * first assertion passed: the file agreeing with itself and not with the JDK is a vintage
 * difference, and the file is what the device will read.
 */
public final class TrailingRuleCheck {

    private static final Path ZONES = Path.of("/usr/share/zoneinfo");

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(ZONES)) {
            System.out.println("no " + ZONES + " on this host — nothing to check");
            return;
        }

        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ZONES, FileVisitOption.FOLLOW_LINKS)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = ZONES.relativize(file).toString();
                if (id.isEmpty() || !Character.isUpperCase(id.charAt(0))) continue;
                ids.add(id);
            }
        }
        Collections.sort(ids);

        int withRules = 0;
        int withoutRules = 0;
        int agreesWithTheJdk = 0;
        int newerThanTheJdk = 0;
        List<String> wrong = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        List<String> vintage = new ArrayList<>();
        List<String> richer = new ArrayList<>();

        for (String id : ids) {
            File file;
            try {
                file = read(ZONES.resolve(id));
            } catch (RuntimeException | IOException notAZoneFile) {
                continue;
            }
            if (file.times.length == 0) continue;

            ZoneOffset endsWith = ZoneOffset.ofTotalSeconds(file.standardAtTheEnd());
            List<ZoneOffsetTransitionRule> rules = TrailingPosixRule.forZone(id, endsWith);

            if (rules.isEmpty()) {
                withoutRules++;
                if (file.saysItHasDaylightSaving()) {
                    refused.add(id + "  " + file.footer);
                }
                continue;
            }
            withRules++;

            // ---- against the file itself ----
            //
            // Every transition the rule states must be one the file states too, to the second. Not
            // the other way round: a POSIX string says two transitions a year and some zones have
            // more — Gaza and Hebron suspend daylight saving for Ramadan, four changes in a year —
            // and the string is then the best two-transition statement there is, which is what the
            // JDK's own tzdb does with the same zone. Containment catches every error of date, time
            // or offset and tolerates only that.
            int year = file.lastFullYear();
            List<String> fromTheFile = file.transitionsIn(year);
            List<String> fromTheRule = fromTheRule(rules, year);
            if (!fromTheFile.containsAll(fromTheRule)) {
                wrong.add(id + "  " + file.footer
                                  + "\n      the file says    " + fromTheFile
                                  + "\n      the rule gives   " + fromTheRule);
                continue;
            }
            if (fromTheFile.size() != fromTheRule.size()) {
                richer.add(id + "  " + file.footer + "  — the file states "
                                   + fromTheFile.size() + " changes in " + year + ", the rule "
                                   + fromTheRule.size());
            }

            // ---- against the host JDK ----
            List<ZoneOffsetTransitionRule> theJdks;
            try {
                theJdks = ZoneId.of(id).getRules().getTransitionRules();
            } catch (RuntimeException unknownToTheJdk) {
                continue;
            }
            if (theJdks.isEmpty()) {
                newerThanTheJdk++;
                continue;
            }
            boolean same = true;
            for (int y = 2038; y <= 2060 && same; y++) {
                same = fromTheRule(rules, y).equals(fromTheRule(theJdks, y));
            }
            if (same) {
                agreesWithTheJdk++;
            } else {
                newerThanTheJdk++;
                vintage.add(id + "  " + file.footer
                                    + "\n      the file gives   " + fromTheRule(rules, 2038)
                                    + "\n      the JDK gives    " + fromTheRule(theJdks, 2038));
            }
        }

        System.out.println("zone files on this host:          " + ids.size());
        System.out.println("  a trailing rule was built:      " + withRules);
        System.out.println("  no rule to build (fixed offset): " + withoutRules);
        System.out.println("  of the rules, agreeing with the JDK: " + agreesWithTheJdk);
        System.out.println("  of the rules, the file is newer:     " + newerThanTheJdk);

        if (!refused.isEmpty()) {
            System.out.println("\nrefused, a rule this cannot represent — these zones keep the"
                                       + " behaviour of before, the last offset for ever:");
            refused.forEach(r -> System.out.println("  " + r));
        }
        if (!richer.isEmpty()) {
            System.out.println("\nthe file states more changes in a year than a POSIX string can"
                                       + " — the explicit transitions govern until the cut-off and"
                                       + " the rule after it, which is what the JDK does too:");
            richer.forEach(r -> System.out.println("  " + r));
        }
        if (!vintage.isEmpty()) {
            System.out.println("\nthe file and the JDK disagree. Each of these reproduced its own"
                                       + " file's transitions exactly, so the file is a newer tzdb"
                                       + " than the JDK's and the file is what a phone reads:");
            vintage.forEach(v -> System.out.println("  " + v));
        }

        if (!wrong.isEmpty()) {
            System.out.println("\nWRONG — the rule does not reproduce the transitions in the same"
                                       + " file:");
            wrong.forEach(w -> System.out.println("  " + w));
            throw new AssertionError(wrong.size() + " zone(s) where the trailing rule and the"
                                             + " explicit transitions in the same file disagree");
        }
        if (withRules < 150) {
            throw new AssertionError("only " + withRules + " zones got a trailing rule, where there"
                                             + " were 195 when this was written — either the files"
                                             + " on this host changed shape or the parse stopped"
                                             + " reading them");
        }
        System.out.println("\nok");
    }

    /** What a rule does in one year, as text, in the order it happens. */
    private static List<String> fromTheRule(List<ZoneOffsetTransitionRule> rules, int year) {
        List<String> all = new ArrayList<>();
        for (ZoneOffsetTransitionRule rule : rules) {
            ZoneOffsetTransition moment = rule.createTransition(year);
            all.add(moment.getInstant() + " " + moment.getOffsetBefore()
                            + "->" + moment.getOffsetAfter());
        }
        Collections.sort(all);
        return all;
    }

    /**
     * As much of a TZif file as this needs: the transition times and the offset at each.
     *
     * <p>Read here rather than taken from {@code ZoneInfoData} because that class is compiled for
     * the device against a boot class path and will not run on a host JVM, and because a check that
     * read the data through the same code as the thing it is checking would be worth less.
     */
    private static final class File {

        final long[] times;
        final int[] offsets;
        final boolean[] daylight;
        final String footer;

        File(long[] times, int[] offsets, boolean[] daylight, String footer) {
            this.times = times;
            this.offsets = offsets;
            this.daylight = daylight;
            this.footer = footer;
        }

        /** The standard offset in force after the last transition. */
        int standardAtTheEnd() {
            for (int i = times.length - 1; i >= 0; i--) {
                if (!daylight[i]) return offsets[i];
            }
            return offsets[offsets.length - 1];
        }

        boolean saysItHasDaylightSaving() {
            return footer != null && footer.indexOf(',') >= 0;
        }

        /** The last year the file has a complete set of transitions for. */
        int lastFullYear() {
            return ZonedDateTime.ofInstant(Instant.ofEpochSecond(times[times.length - 1]),
                                           ZoneOffset.UTC).getYear() - 1;
        }

        List<String> transitionsIn(int year) {
            List<String> all = new ArrayList<>();
            for (int i = 1; i < times.length; i++) {
                int of = ZonedDateTime.ofInstant(Instant.ofEpochSecond(times[i]), ZoneOffset.UTC)
                        .getYear();
                if (of != year) continue;
                if (offsets[i] == offsets[i - 1]) continue;   // an abbreviation change only
                all.add(Instant.ofEpochSecond(times[i])
                                + " " + ZoneOffset.ofTotalSeconds(offsets[i - 1])
                                + "->" + ZoneOffset.ofTotalSeconds(offsets[i]));
            }
            Collections.sort(all);
            return all;
        }
    }

    private static File read(Path path) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(path));
        if (bytes.limit() < 44 || bytes.get(0) != 'T' || bytes.get(1) != 'Z'
                || bytes.get(2) != 'i' || bytes.get(3) != 'f') {
            throw new IllegalArgumentException("not a TZif file: " + path);
        }
        boolean second = bytes.get(4) >= '2';

        bytes.position(20);
        int isutcnt = bytes.getInt();
        int isstdcnt = bytes.getInt();
        int leapcnt = bytes.getInt();
        int timecnt = bytes.getInt();
        int typecnt = bytes.getInt();
        int charcnt = bytes.getInt();

        if (second) {
            int firstBlock = timecnt * 5 + typecnt * 6 + charcnt + leapcnt * 8 + isstdcnt + isutcnt;
            bytes.position(44 + firstBlock + 20);
            isutcnt = bytes.getInt();
            isstdcnt = bytes.getInt();
            leapcnt = bytes.getInt();
            timecnt = bytes.getInt();
            typecnt = bytes.getInt();
            charcnt = bytes.getInt();
            bytes.position(bytes.position() + 0);
        }

        long[] times = new long[timecnt];
        for (int i = 0; i < timecnt; i++) {
            times[i] = second ? bytes.getLong() : bytes.getInt();
        }
        int[] type = new int[timecnt];
        for (int i = 0; i < timecnt; i++) {
            type[i] = bytes.get() & 0xff;
        }
        int[] offsetOfType = new int[typecnt];
        boolean[] daylightOfType = new boolean[typecnt];
        for (int i = 0; i < typecnt; i++) {
            offsetOfType[i] = bytes.getInt();
            daylightOfType[i] = bytes.get() != 0;
            bytes.get();                                      // the abbreviation index
        }

        int[] offsets = new int[timecnt];
        boolean[] daylight = new boolean[timecnt];
        for (int i = 0; i < timecnt; i++) {
            offsets[i] = offsetOfType[type[i]];
            daylight[i] = daylightOfType[type[i]];
        }

        String footer = null;
        if (second) {
            byte[] all = bytes.array();
            if (all[all.length - 1] == '\n') {
                int opens = -1;
                for (int i = all.length - 2; i >= 0 && i > all.length - 300; i--) {
                    if (all[i] == '\n') {
                        opens = i;
                        break;
                    }
                }
                if (opens >= 0 && opens < all.length - 2) {
                    footer = new String(all, opens + 1, all.length - opens - 2, "US-ASCII");
                }
            }
        }
        return new File(times, offsets, daylight, footer);
    }

    private TrailingRuleCheck() {
    }
}
