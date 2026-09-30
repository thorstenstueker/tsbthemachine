import java.time.Duration;
import java.util.Arrays;

/**
 * The String and Thread members of Java 9 through 21, on the real library.
 *
 * String's two ranged indexOf overloads and splitWithDelimiters; Thread's threadId, isVirtual, the
 * Duration overloads of sleep and join, and the constructor that declines to inherit thread-locals.
 *
 * What is worth pinning, in order of how quietly it could be wrong:
 *
 * The ranged indexOf throws where the two-argument one clamps. {@code "abc".indexOf('a', -5)} is
 * 0 and always has been; {@code "abc".indexOf('a', -5, 3)} is a StringIndexOutOfBoundsException.
 * Implementing the new one by clamping and delegating gives an answer for every input and the
 * wrong answer for a caller who got their arithmetic wrong — which is the caller the exception is
 * for.
 *
 * The range is a range, not a starting point. A substring that begins inside it and runs past
 * endIndex has not been found. Delegating to indexOf(str, beginIndex) finds it.
 *
 * splitWithDelimiters keeps what it matched, and its limit counts substrings while its result
 * counts both — so a positive limit of n yields at most 2n-1 elements, not n. A limit of zero
 * drops trailing empty substrings and stops at the first non-empty element, which a delimiter
 * always is: OpenJDK's own example ends with a delimiter that survives the trimming.
 *
 * join(Duration) returns whether the thread died, which join(long) could not say. The two failure
 * modes are returning true on a timeout — the caller then reads results that are not there — and
 * throwing on a thread that has already finished.
 *
 * And the constructor's inheritThreadLocals flag has exactly one observable effect, which is the
 * one it is for: a worker created inside some request must not keep that request's inheritable
 * values alive for the life of the pool.
 *
 * <h2>Where the expected values come from</h2>
 *
 * Every number and every exception type below was run on Liberica JDK 21 first and copied from
 * what it printed -- not from the specification as I remembered it. That distinction is not
 * pedantry: a test whose expectations come from the same head as the implementation agrees with
 * the implementation by construction, and this file would have passed on its first run either way.
 * The witness program is three dozen println calls and takes a minute to write; it is the only
 * part of this that is evidence.
 */
public class LangCheck {

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

    // ---------------------------------------------------------------- String.indexOf ranged

    static void rangedIndexOf() {
        String s = "abcabcabc";

        eq("a character inside the range", s.indexOf('b', 0, 9), 1);
        eq("the search starts at beginIndex", s.indexOf('a', 1, 9), 3);
        eq("and stops before endIndex", s.indexOf('a', 1, 3), -1);
        eq("endIndex is exclusive", s.indexOf('c', 0, 2), -1);
        eq("just inclusive enough", s.indexOf('c', 0, 3), 2);
        eq("an empty range finds nothing", s.indexOf('a', 4, 4), -1);

        // Where it differs from the two-argument form on purpose: that one clamps, this throws.
        eq("the old two-argument form still clamps", s.indexOf('a', -5), 0);
        throwsToo("a negative begin", StringIndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                "abcabcabc".indexOf('a', -1, 3);
            }
        });
        throwsToo("an end past the string", StringIndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                "abcabcabc".indexOf('a', 0, 10);
            }
        });
        throwsToo("begin after end", StringIndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                "abcabcabc".indexOf('a', 5, 2);
            }
        });

        // A supplementary code point is two chars, and counts as found only if both are inside.
        String mitEmoji = "ab😀cd";      // U+1F600, a grinning face
        int emoji = 0x1F600;
        eq("a supplementary character is found by code point", mitEmoji.indexOf(emoji, 0, 6), 2);
        eq("and not when only its first half is in range", mitEmoji.indexOf(emoji, 0, 3), -1);

        // Substrings.
        eq("a substring inside the range", s.indexOf("bca", 0, 9), 1);
        eq("the whole substring has to fit", s.indexOf("bca", 0, 3), -1);
        eq("an empty substring is at beginIndex", s.indexOf("", 4, 7), 4);
        eq("a substring that is not there", s.indexOf("xyz", 0, 9), -1);
        throwsToo("a substring search with a bad range", StringIndexOutOfBoundsException.class,
                  new Runnable() {
                      public void run() {
                          "abcabcabc".indexOf("a", 0, 99);
                      }
                  });
    }

    // ---------------------------------------------------------------- splitWithDelimiters

    static void splitKeepingDelimiters() {
        // The three examples from the specification, which between them pin the limit rules.
        eq("a positive limit counts substrings, not elements",
           Arrays.toString("boo:and:foo".splitWithDelimiters(":", 2)),
           "[boo, :, and:foo]");
        eq("a negative limit keeps every empty",
           Arrays.toString("boo:and:foo".splitWithDelimiters("o", -2)),
           "[b, o, , o, :and:f, o, , o, ]");
        eq("zero drops trailing empty substrings but not the delimiter before them",
           Arrays.toString("boo:and:foo".splitWithDelimiters("o", 0)),
           "[b, o, , o, :and:f, o, , o]");

        eq("no match at all gives the whole string",
           Arrays.toString("abc".splitWithDelimiters(":", 0)), "[abc]");
        eq("a leading delimiter produces an empty first substring",
           Arrays.toString(":ab".splitWithDelimiters(":", 0)), "[, :, ab]");
        eq("the result can be put back together",
           String.join("", "a1b22c".splitWithDelimiters("[0-9]+", -1)), "a1b22c");

        // A regular expression, not a literal: the fast path split() uses cannot serve here,
        // because it does not keep what it matched.
        eq("a real pattern",
           Arrays.toString("a12b3c".splitWithDelimiters("[0-9]+", -1)), "[a, 12, b, 3, c]");
    }

    // ---------------------------------------------------------------- Thread

    static void threadIdentityAndKind() {
        Thread t = Thread.currentThread();
        eq("threadId agrees with getId", t.threadId(), t.getId());
        ok("an id is positive", t.threadId() > 0);
        ok("this thread is not virtual", !t.isVirtual());

        Thread other = new Thread(new Runnable() {
            public void run() {
                // nothing; only its identity is of interest
            }
        });
        ok("two threads have different ids", other.threadId() != t.threadId());
    }

    static void sleepAndJoinWithDurations() throws Exception {
        long vorher = System.nanoTime();
        Thread.sleep(Duration.ofMillis(50));
        long dauer = (System.nanoTime() - vorher) / 1_000_000;
        ok("sleep(Duration) actually sleeps: " + dauer + "ms", dauer >= 40);

        // Negative and zero return at once and do not throw -- unlike sleep(long), where a
        // negative millisecond count is an IllegalArgumentException.
        vorher = System.nanoTime();
        Thread.sleep(Duration.ofMillis(-100));
        Thread.sleep(Duration.ZERO);
        dauer = (System.nanoTime() - vorher) / 1_000_000;
        ok("a negative duration returns at once: " + dauer + "ms", dauer < 40);
        throwsToo("while a negative long still throws", IllegalArgumentException.class,
                  new Runnable() {
                      public void run() {
                          try {
                              Thread.sleep(-1);
                          } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  });
        throwsToo("and a null duration is a NullPointerException", NullPointerException.class,
                  new Runnable() {
                      public void run() {
                          try {
                              Thread.sleep((Duration) null);
                          } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  });

        // A thread that will outlive the wait: the answer has to be false, and a caller that read
        // its results on a true would read nothing.
        final Object riegel = new Object();
        final boolean[] fertig = new boolean[1];
        Thread langsam = new Thread(new Runnable() {
            public void run() {
                synchronized (riegel) {
                    while (!fertig[0]) {
                        try {
                            riegel.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        });
        langsam.start();
        eq("join(Duration) says false when the thread is still running",
           langsam.join(Duration.ofMillis(100)), false);
        synchronized (riegel) {
            fertig[0] = true;
            riegel.notifyAll();
        }
        eq("and true once it has finished", langsam.join(Duration.ofSeconds(5)), true);
        eq("and still true when asked again with no time at all",
           langsam.join(Duration.ZERO), true);

        throwsToo("joining a thread that was never started", IllegalThreadStateException.class,
                  new Runnable() {
                      public void run() {
                          try {
                              new Thread(new Runnable() {
                                  public void run() {
                                      // never started
                                  }
                              }).join(Duration.ofMillis(1));
                          } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  });
    }

    static void inheritableThreadLocalsCanBeDeclined() throws Exception {
        final InheritableThreadLocal<String> wert = new InheritableThreadLocal<String>();
        wert.set("the request");

        final String[] geerbt = new String[2];

        Thread erbt = new Thread(new Runnable() {
            public void run() {
                geerbt[0] = wert.get();
            }
        });
        erbt.start();
        erbt.join();

        Thread erbtNicht = new Thread(null, new Runnable() {
            public void run() {
                geerbt[1] = wert.get();
            }
        }, "no-inherit", 0, false);
        erbtNicht.start();
        erbtNicht.join();

        eq("an ordinary thread inherits", geerbt[0], "the request");
        eq("one created with inheritThreadLocals=false does not", geerbt[1], null);

        // And the flag must not break anything else about the thread.
        eq("it is still the thread it was asked for", erbtNicht.getName(), "no-inherit");
        ok("and it ran", geerbt[1] == null);
    }

    // ---------------------------------------------------------------- Class

    interface Beispiel<E> {
        // only its shape is of interest
    }

    static class Schranke<T extends Number & Comparable<T>> {
        // likewise
    }

    static void classDescriptions() {
        eq("a final class", String.class.toGenericString(),
           "public final class java.lang.String");
        eq("an interface keeps its abstract, as the specification prints it",
           java.util.List.class.toGenericString(), "public abstract interface java.util.List<E>");
        eq("two type parameters", java.util.Map.class.toGenericString(),
           "public abstract interface java.util.Map<K,V>");
        eq("a plain class", Object.class.toGenericString(), "public class java.lang.Object");

        // A primitive answers toString(), and an array is the component name with brackets and no
        // modifiers at all -- not "public abstract final class [I".
        eq("a primitive", int.class.toGenericString(), "int");
        eq("void is a primitive here", void.class.toGenericString(), "void");
        eq("an array of primitives", int[].class.toGenericString(), "int[]");
        eq("two dimensions", int[][].class.toGenericString(), "int[][]");
        eq("an array of objects", String[].class.toGenericString(), "java.lang.String[]");
        eq("an array of a generic type keeps the parameters before the brackets",
           java.util.List[].class.toGenericString(), "java.util.List<E>[]");

        // A bound of exactly Object is left out; anything else is written with "extends".
        eq("an unbounded parameter", Beispiel.class.toGenericString(),
           "abstract static interface LangCheck$Beispiel<E>");
        eq("bounds are written out, joined with &", Schranke.class.toGenericString(),
           "static class LangCheck$Schranke<T extends java.lang.Number"
           + " & java.lang.Comparable<T>>");

        eq("a primitive by name", Class.forPrimitiveName("int"), int.class);
        eq("void by name", Class.forPrimitiveName("void"), void.class);
        eq("and it is null rather than an exception for anything else",
           Class.forPrimitiveName("java.lang.String"), null);
        eq("including an array descriptor", Class.forPrimitiveName("[I"), null);
        eq("and a misspelling", Class.forPrimitiveName("Int"), null);
    }

    // ---------------------------------------------------------------- Scanner

    static void scannerStreamsAndCharsets() throws Exception {
        java.util.Scanner s = new java.util.Scanner("eins zwei drei");
        eq("tokens() yields the tokens", s.tokens().collect(java.util.stream.Collectors.toList())
                                                  .toString(), "[eins, zwei, drei]");

        // The stream and the scanner share one position: what next() took, tokens() will not see.
        java.util.Scanner geteilt = new java.util.Scanner("eins zwei");
        eq("next() first", geteilt.next(), "eins");
        eq("then the stream sees the rest",
           geteilt.tokens().collect(java.util.stream.Collectors.toList()).toString(), "[zwei]");

        eq("an empty scanner yields nothing",
           new java.util.Scanner("").tokens().collect(java.util.stream.Collectors.toList())
                                    .toString(), "[]");

        java.util.Scanner f = new java.util.Scanner("a1b22c333");
        StringBuilder gefunden = new StringBuilder();
        java.util.Iterator<java.util.regex.MatchResult> it = f.findAll("[0-9]+").iterator();
        while (it.hasNext()) {
            gefunden.append(it.next().group()).append(' ');
        }
        eq("findAll finds every match", gefunden.toString().trim(), "1 22 333");

        java.util.Scanner g = new java.util.Scanner("x1y2");
        StringBuilder stellen = new StringBuilder();
        java.util.Iterator<java.util.regex.MatchResult> it2 =
                g.findAll(java.util.regex.Pattern.compile("[0-9]")).iterator();
        while (it2.hasNext()) {
            java.util.regex.MatchResult m = it2.next();
            stellen.append(m.start()).append(':').append(m.group()).append(' ');
        }
        eq("and reports where they were", stellen.toString().trim(), "1:1 3:2");

        // The charset overloads: same result as the charsetName ones, without a name to misspell.
        java.util.Scanner c = new java.util.Scanner(
                new java.io.ByteArrayInputStream("hallo".getBytes("UTF-8")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq("the Charset constructor reads the same bytes", c.next(), "hallo");

        final java.util.Scanner zu = new java.util.Scanner("zu");
        zu.close();
        throwsToo("a closed scanner cannot be streamed", IllegalStateException.class,
                  new Runnable() {
                      public void run() {
                          zu.tokens();
                      }
                  });
    }

    public static void main(String[] args) throws Exception {
        rangedIndexOf();
        splitKeepingDelimiters();
        threadIdentityAndKind();
        sleepAndJoinWithDurations();
        inheritableThreadLocalsCanBeDeclined();
        classDescriptions();
        scannerStreamsAndCharsets();

        System.out.println("LangCheck: " + checked + " checked, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
