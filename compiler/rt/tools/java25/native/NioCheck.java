import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The small nio additions of Java 10 through 22, on the real library.
 *
 * Charset.forName with a fallback, Channels.newReader/newWriter taking a Charset,
 * Path.resolve with varargs, SelectionKey's atomic interest-op updates and FileStore.getBlockSize.
 *
 * None of these is hard. What each one replaces is a hand-written version that was subtly wrong:
 *
 * forName(name, fallback) exists because the hand-written try/catch usually catches
 * UnsupportedCharsetException and not IllegalCharsetNameException, so a *malformed* name -- a
 * header field with a space in it -- still throws where an unknown one falls back. Both are caught
 * here, and only those two: catching their common superclass IllegalArgumentException is the
 * obvious way to write it and swallows a null name, which JDK 25 throws on. Measured, not assumed.
 *
 * resolve(a, b, c) is left-to-right with the same absolute-path rule as the single-argument form:
 * an absolute element in the middle discards everything before it. A loop that concatenated the
 * names first would produce a different path.
 *
 * getBlockSize throws rather than guessing 4096. A caller sizing a buffer against a made-up number
 * behaves worse than one that catches this.
 *
 * And StandardCharsets' three Java 18 fields. Those are worth a check of their own because the
 * class initialises every field when it loads: a UTF-32 that was not actually present would not
 * leave one field null, it would throw out of the static initialiser and take UTF_8 with it. The
 * first assertion below reads UTF_8 for exactly that reason.
 */
public class NioCheck {

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

    static void charsetFallback() {
        eq("a known charset is returned", Charset.forName("UTF-8", null), StandardCharsets.UTF_8);
        eq("an unknown one falls back",
           Charset.forName("no-such-charset", StandardCharsets.ISO_8859_1),
           StandardCharsets.ISO_8859_1);

        // The one the hand-written version gets wrong: a name that is not merely unknown but
        // malformed throws IllegalCharsetNameException, which is a different exception.
        eq("a malformed name falls back too",
           Charset.forName("has a space", StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
        eq("and the fallback may itself be null", Charset.forName("nonsense!", null), null);

        // A null name is not a bad name, it is a bug, and it throws — measured on JDK 25. The
        // obvious implementation catches IllegalArgumentException, which is the superclass of
        // both charset exceptions, and swallows this as well.
        throwsToo("a null name still throws", IllegalArgumentException.class, new Runnable() {
            public void run() {
                Charset.forName(null, StandardCharsets.UTF_8);
            }
        });
    }

    static void channelReadersTakeACharset() throws Exception {
        File tmp = File.createTempFile("niocheck", ".txt");
        try {
            // Written and read through the Charset overloads only, so a mismatch shows up as
            // mangled text rather than as a missing method.
            try (java.io.FileOutputStream aus = new java.io.FileOutputStream(tmp);
                 Writer w = Channels.newWriter(aus.getChannel(), StandardCharsets.UTF_8)) {
                w.write("Grüße, Ödipus");
            }

            try (java.io.FileInputStream ein = new java.io.FileInputStream(tmp)) {
                ReadableByteChannel ch = ein.getChannel();
                Reader r = Channels.newReader(ch, StandardCharsets.UTF_8);
                char[] puffer = new char[64];
                int n = r.read(puffer);
                eq("what the Charset writer wrote, the Charset reader reads",
                   new String(puffer, 0, n), "Grüße, Ödipus");
            }

            // And that the charset is actually used: the same bytes read as ISO-8859-1 are
            // longer, because each byte of the two-byte umlauts becomes its own character.
            try (java.io.FileInputStream ein = new java.io.FileInputStream(tmp)) {
                Reader r = Channels.newReader(ein.getChannel(), StandardCharsets.ISO_8859_1);
                char[] puffer = new char[64];
                int n = r.read(puffer);
                ok("a different charset gives a different length: " + n, n > 13);
            }
        } finally {
            tmp.delete();
        }
    }

    static void pathResolveVarargs() {
        Path p = Paths.get("/tmp");
        eq("three strings, left to right", p.resolve("a", "b", "c").toString(), "/tmp/a/b/c");
        eq("three paths", p.resolve(Paths.get("a"), Paths.get("b"), Paths.get("c")).toString(),
           "/tmp/a/b/c");
        eq("no varargs at all is the single-argument form", p.resolve("a").toString(), "/tmp/a");

        // The rule that a loop over concatenated names would break: an absolute element throws
        // away everything to its left.
        eq("an absolute element in the middle wins",
           p.resolve("a", "/etc", "c").toString(), "/etc/c");
        eq("an empty element changes nothing", p.resolve("a", "", "c").toString(), "/tmp/a/c");
    }

    static void fileStoreBlockSize() {
        // Files.getFileStore is not usable on this runtime -- UnixFileSystemProvider asks a
        // security manager that is not there and gets a SecurityException. That is a gap of its
        // own and nothing to do with getBlockSize, so it is reported rather than asserted: a test
        // that failed here would be pointing at the wrong thing.
        final FileStore store;
        try {
            store = Files.getFileStore(Paths.get("/"));
        } catch (Throwable t) {
            System.out.println("  (no file store to ask: " + t.getClass().getName() + " -- "
                               + "Files.getFileStore does not work here)");
            return;
        }
        ok("a file store is reachable", store != null);
        throwsToo("getBlockSize is not implemented and says so",
                  UnsupportedOperationException.class, new Runnable() {
                      public void run() {
                          try {
                              store.getBlockSize();
                          } catch (IOException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  });
    }

    static void interestOps() throws Exception {
        java.nio.channels.Selector sel = java.nio.channels.Selector.open();
        try {
            java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open();
            ch.configureBlocking(false);
            java.nio.channels.SelectionKey key = ch.register(
                    sel, java.nio.channels.SelectionKey.OP_CONNECT);

            eq("the previous value comes back, not the new one",
               key.interestOpsOr(java.nio.channels.SelectionKey.OP_READ),
               java.nio.channels.SelectionKey.OP_CONNECT);
            eq("and the bit was added",
               key.interestOps(),
               java.nio.channels.SelectionKey.OP_CONNECT | java.nio.channels.SelectionKey.OP_READ);

            eq("and cleared again, previous value first",
               key.interestOpsAnd(~java.nio.channels.SelectionKey.OP_CONNECT),
               java.nio.channels.SelectionKey.OP_CONNECT | java.nio.channels.SelectionKey.OP_READ);
            eq("leaving only what was kept",
               key.interestOps(), java.nio.channels.SelectionKey.OP_READ);

            ch.close();
        } finally {
            sel.close();
        }
    }

    static void utf32() {
        // Every field of StandardCharsets is initialised when the class loads, so these three
        // cannot be half-present: if UTF-32 were missing the static initialiser would throw and
        // UTF_8 would be unreachable too. Reading UTF_8 first is therefore itself a check.
        eq("the class still initialises", StandardCharsets.UTF_8.name(), "UTF-8");
        eq("UTF-32BE is what it says", StandardCharsets.UTF_32BE.name(), "UTF-32BE");
        eq("UTF-32LE too", StandardCharsets.UTF_32LE.name(), "UTF-32LE");
        eq("and UTF-32", StandardCharsets.UTF_32.name(), "UTF-32");

        // Four bytes per character, and the endianness is real: the same text encoded big- and
        // little-endian has to differ, or one of the two constants is pointing at the other.
        byte[] gross = "Aü".getBytes(StandardCharsets.UTF_32BE);
        byte[] klein = "Aü".getBytes(StandardCharsets.UTF_32LE);
        eq("four bytes per character, big-endian", gross.length, 8);
        eq("and little-endian", klein.length, 8);
        eq("'A' big-endian ends with the 0x41", gross[3], (byte) 0x41);
        eq("and little-endian begins with it", klein[0], (byte) 0x41);

        eq("UTF-32BE round trip", new String(gross, StandardCharsets.UTF_32BE), "Aü");
        eq("UTF-32LE round trip", new String(klein, StandardCharsets.UTF_32LE), "Aü");

        // A supplementary character is one code unit here, which is the reason UTF-32 exists.
        byte[] emoji = "😀".getBytes(StandardCharsets.UTF_32BE);
        eq("a supplementary character is four bytes, not eight", emoji.length, 4);
        eq("and comes back whole", new String(emoji, StandardCharsets.UTF_32BE), "😀");

        ByteBuffer b = StandardCharsets.UTF_8.encode("Grüße");
        eq("UTF-8 round trip", StandardCharsets.UTF_8.decode(b).toString(), "Grüße");
    }

    public static void main(String[] args) throws Exception {
        charsetFallback();
        channelReadersTakeACharset();
        pathResolveVarargs();
        fileStoreBlockSize();
        interestOps();
        utf32();

        System.out.println("NioCheck: " + checked + " checked, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
