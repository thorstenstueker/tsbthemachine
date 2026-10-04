import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;

/**
 * {@code Socket} and {@code ServerSocket}'s {@code SocketOption} family (Java 9), and
 * {@code FileWriter}'s {@code Charset} constructors (Java 11), on the real library.
 *
 * <h2>Why FileWriter is in here and why it matters more than it looks</h2>
 *
 * <p>Without those four constructors a {@code FileWriter} encodes in the platform's default
 * charset and there is no way to say otherwise — so a program that wanted UTF-8 had to write
 * {@code new OutputStreamWriter(new FileOutputStream(f), UTF_8)} and give up the name. On a phone
 * the default is UTF-8 and nothing shows; it is the file written on somebody's Windows machine
 * that comes back wrong, which is the worst place for a fault to live.
 *
 * <p>So the check writes a word with an umlaut in it and reads the <em>bytes</em> back. Reading
 * the text back through a reader with the same charset would pass with any charset at all,
 * including the wrong one.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>JDK 25, asked directly on 04.10.2026. The exception for a null option is
 * {@code NullPointerException} and not {@code UnsupportedOperationException} — which is what this
 * runtime answered before, because the impl compares by identity against the
 * {@code StandardSocketOptions} constants and a null name missed every branch. That is
 * indistinguishable from "nobody implements that option".
 *
 * <p>The supported set itself is printed rather than asserted, for the reason {@code DatagramCheck}
 * gives at length: a modern JDK goes through NIO and reports a different list than libcore's impl,
 * and both are defensible.
 */
public class SocketOptionCheck {

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

    interface Aufruf {
        void run() throws Exception;
    }

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

    static void einSocket() throws IOException {
        final Socket s = new Socket();
        try {
            Set<SocketOption<?>> supported = s.supportedOptions();
            System.out.println("  Socket.supportedOptions() = " + supported);

            ok("SO_RCVBUF supported", supported.contains(StandardSocketOptions.SO_RCVBUF));
            ok("SO_REUSEADDR supported", supported.contains(StandardSocketOptions.SO_REUSEADDR));
            ok("TCP_NODELAY supported", supported.contains(StandardSocketOptions.TCP_NODELAY));

            throwsIt("Socket.supportedOptions() is unmodifiable",
                     UnsupportedOperationException.class,
                     new Aufruf() {
                         public void run() {
                             s.supportedOptions().add(StandardSocketOptions.SO_BROADCAST);
                         }
                     });

            ok("Socket.setOption returns this",
               s.setOption(StandardSocketOptions.TCP_NODELAY, true) == s);
            ok("Socket.getOption reads it back",
               Boolean.TRUE.equals(s.getOption(StandardSocketOptions.TCP_NODELAY)));

            // The buffer size: greater-or-equal, because the kernel may round up and on Linux it
            // doubles what it is given.
            s.setOption(StandardSocketOptions.SO_RCVBUF, 8192);
            ok("Socket SO_RCVBUF round trip",
               s.getOption(StandardSocketOptions.SO_RCVBUF) >= 8192);

            throwsIt("Socket.setOption(null, v)", NullPointerException.class,
                     new Aufruf() {
                         public void run() throws Exception {
                             s.setOption(null, 1);
                         }
                     });
            throwsIt("Socket.getOption(null)", NullPointerException.class,
                     new Aufruf() {
                         public void run() throws Exception {
                             s.getOption(null);
                         }
                     });
            throwsIt("Socket, an unsupported option", UnsupportedOperationException.class,
                     new Aufruf() {
                         public void run() throws Exception {
                             s.setOption(StandardSocketOptions.SO_BROADCAST, true);
                         }
                     });
        } finally {
            s.close();
        }

        ok("Socket.supportedOptions() answers after close", !s.supportedOptions().isEmpty());
        throwsIt("Socket.setOption after close", IOException.class,
                 new Aufruf() {
                     public void run() throws Exception {
                         s.setOption(StandardSocketOptions.TCP_NODELAY, true);
                     }
                 });
    }

    static void einServerSocket() throws IOException {
        final ServerSocket s = new ServerSocket();
        try {
            Set<SocketOption<?>> supported = s.supportedOptions();
            System.out.println("  ServerSocket.supportedOptions() = " + supported);

            ok("ServerSocket SO_RCVBUF supported",
               supported.contains(StandardSocketOptions.SO_RCVBUF));
            ok("ServerSocket SO_REUSEADDR supported",
               supported.contains(StandardSocketOptions.SO_REUSEADDR));

            ok("ServerSocket.setOption returns this",
               s.setOption(StandardSocketOptions.SO_REUSEADDR, true) == s);
            ok("ServerSocket SO_REUSEADDR round trip",
               Boolean.TRUE.equals(s.getOption(StandardSocketOptions.SO_REUSEADDR)));

            throwsIt("ServerSocket.setOption(null, v)", NullPointerException.class,
                     new Aufruf() {
                         public void run() throws Exception {
                             s.setOption(null, 1);
                         }
                     });
            throwsIt("ServerSocket.getOption(null)", NullPointerException.class,
                     new Aufruf() {
                         public void run() throws Exception {
                             s.getOption(null);
                         }
                     });
        } finally {
            s.close();
        }

        ok("ServerSocket.supportedOptions() answers after close", !s.supportedOptions().isEmpty());
        throwsIt("ServerSocket.setOption after close", IOException.class,
                 new Aufruf() {
                     public void run() throws Exception {
                         s.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                     }
                 });
    }

    static void dateiSchreiber() throws IOException {
        // An umlaut, because it is one byte in Latin-1 and two in UTF-8. A word made only of
        // ASCII would be written identically by every charset in the room and would prove
        // nothing at all.
        String wort = "Grüße";
        byte[] utf8 = wort.getBytes("UTF-8");
        byte[] latin1 = wort.getBytes("ISO-8859-1");
        ok("the test word distinguishes the charsets", !Arrays.equals(utf8, latin1));

        File f = File.createTempFile("tsb-writer", ".txt");
        try {
            FileWriter w = new FileWriter(f, Charset.forName("UTF-8"));
            w.write(wort);
            w.close();
            ok("FileWriter(File, Charset) wrote UTF-8",
               Arrays.equals(utf8, Files.readAllBytes(f.toPath())));

            w = new FileWriter(f.getPath(), Charset.forName("ISO-8859-1"));
            w.write(wort);
            w.close();
            ok("FileWriter(String, Charset) wrote Latin-1",
               Arrays.equals(latin1, Files.readAllBytes(f.toPath())));

            // And the appending pair, which is the half that would be wrong if the boolean were
            // passed to the wrong FileOutputStream constructor.
            w = new FileWriter(f, Charset.forName("UTF-8"), false);
            w.write(wort);
            w.close();
            w = new FileWriter(f, Charset.forName("UTF-8"), true);
            w.write(wort);
            w.close();
            byte[] twice = new byte[utf8.length * 2];
            System.arraycopy(utf8, 0, twice, 0, utf8.length);
            System.arraycopy(utf8, 0, twice, utf8.length, utf8.length);
            ok("FileWriter(File, Charset, true) appended",
               Arrays.equals(twice, Files.readAllBytes(f.toPath())));

            w = new FileWriter(f.getPath(), Charset.forName("UTF-8"), false);
            w.write(wort);
            w.close();
            eq("FileWriter(String, Charset, false) truncated",
               Files.readAllBytes(f.toPath()).length, utf8.length);
        } finally {
            f.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Socket options and FileWriter charsets on "
                                   + System.getProperty("os.name") + " "
                                   + System.getProperty("os.arch"));

        einSocket();
        einServerSocket();
        dateiSchreiber();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
