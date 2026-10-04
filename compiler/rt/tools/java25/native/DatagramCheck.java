import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.util.Set;

/**
 * {@code DatagramSocket}'s {@code SocketOption} family (Java 9) and its {@code SocketAddress}
 * multicast operations (Java 17), on the real library.
 *
 * <h2>Why the JDK's own answers cannot simply be copied here</h2>
 *
 * <p>Measured on JDK 25, a plain {@code DatagramSocket} reports ten supported options and accepts
 * {@code IP_MULTICAST_TTL} on a socket that is not a {@code MulticastSocket} at all. This runtime's
 * {@code DatagramSocketImpl} is libcore's, it translates seven named options into the legacy
 * {@code SocketOptions} integers, and it refuses the three multicast ones unless the socket really
 * is a {@code MulticastSocket}. Neither is wrong: a modern JDK goes through NIO, and the operating
 * system is happy to set a multicast TTL on any UDP socket.
 *
 * <p>So what is checked is the contract both must keep, and the platform's own list is printed
 * rather than asserted. A test that pinned the JDK's ten options would fail here while the
 * implementation was right — and a test that pinned <em>ours</em> would stop saying anything the
 * day the impl gained an option.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>JDK 25, asked directly on 04.10.2026:
 *
 * <pre>
 *     setOption(null, v)                 -&gt;  NullPointerException
 *     getOption(null)                    -&gt;  NullPointerException
 *     joinGroup(null, null)              -&gt;  IllegalArgumentException
 *     joinGroup(unsupported address)      -&gt;  IllegalArgumentException
 *     joinGroup(a unicast address, null)  -&gt;  SocketException
 *     leaveGroup(a unicast address, null) -&gt;  SocketException
 *     after close(): setOption, getOption -&gt;  IOException (see below)
 *     after close(): supportedOptions()   -&gt;  still answers
 * </pre>
 *
 * <p><b>The one place this deliberately differs, and it is checked as the supertype.</b> JDK 25
 * answers a closed socket with {@code ClosedChannelException}, because its socket is an NIO
 * channel underneath. This class answers {@code SocketException("Socket is closed")}, which is
 * what every other method in it has always answered and what libcore does. Both are
 * {@code IOException}, so a caller catching that sees no difference; a caller catching
 * {@code SocketException} specifically sees a difference that is already there in
 * {@code send}, {@code receive} and {@code connect}. Asserting {@code IOException} says what is
 * actually promised instead of pinning one of two defensible answers.
 *
 * <p>The NPE on a null option is the one this found. Without it the call reaches the impl, whose
 * identity comparisons against the {@code StandardSocketOptions} constants all miss, and the
 * answer is {@code UnsupportedOperationException} — indistinguishable from "nobody implements
 * that option", which is the wrong thing to tell somebody who passed null by accident.
 */
public class DatagramCheck {

    static int checked;
    static int failed;

    static void ok(String what, boolean condition) {
        checked++;
        if (!condition) {
            failed++;
            System.out.println("  MISMATCH " + what);
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

    static void optionen() throws IOException {
        DatagramSocket s = new DatagramSocket();
        try {
            Set<SocketOption<?>> supported = s.supportedOptions();
            System.out.println("  supportedOptions() = " + supported);

            // The four this impl always translates. Asserted rather than printed, because they
            // are what supportedOptions() is for: a caller deciding whether to try.
            ok("SO_RCVBUF supported", supported.contains(StandardSocketOptions.SO_RCVBUF));
            ok("SO_SNDBUF supported", supported.contains(StandardSocketOptions.SO_SNDBUF));
            ok("SO_REUSEADDR supported", supported.contains(StandardSocketOptions.SO_REUSEADDR));
            ok("IP_TOS supported", supported.contains(StandardSocketOptions.IP_TOS));

            // And the set is not somebody's to edit. An unmodifiable view here is the difference
            // between reading what a socket supports and appearing to change it.
            throwsIt("supportedOptions() is unmodifiable", UnsupportedOperationException.class,
                     () -> s.supportedOptions().add(StandardSocketOptions.SO_LINGER));

            // The option it says it supports, set and read back. Greater-or-equal and not equal:
            // the kernel is allowed to round a buffer size up, and on Linux it doubles it.
            ok("setOption returns this",
               s.setOption(StandardSocketOptions.SO_RCVBUF, 8192) == s);
            ok("getOption reads it back",
               s.getOption(StandardSocketOptions.SO_RCVBUF) >= 8192);

            ok("SO_REUSEADDR round trip",
               Boolean.TRUE.equals(s.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                                           .getOption(StandardSocketOptions.SO_REUSEADDR)));

            throwsIt("setOption(null, v)", NullPointerException.class,
                     () -> s.setOption(null, 1));
            throwsIt("getOption(null)", NullPointerException.class,
                     () -> s.getOption(null));

            // An option nobody here implements. SO_LINGER is a stream-socket option and has no
            // meaning for UDP, so it is the honest example rather than an invented constant.
            throwsIt("an unsupported option", UnsupportedOperationException.class,
                     () -> s.setOption(StandardSocketOptions.SO_LINGER, 1));
        } finally {
            s.close();
        }

        // After close: options still answer, operations do not. The first is the documented
        // oddity — what a socket *supports* does not change when it shuts — and it is the one a
        // careless implementation gets wrong by guarding every method the same way.
        ok("supportedOptions() answers after close", !s.supportedOptions().isEmpty());
        throwsIt("setOption after close", IOException.class,
                 () -> s.setOption(StandardSocketOptions.SO_RCVBUF, 4096));
        throwsIt("getOption after close", IOException.class,
                 () -> s.getOption(StandardSocketOptions.SO_RCVBUF));
    }

    static void gruppen() throws IOException {
        DatagramSocket s = new DatagramSocket();
        try {
            throwsIt("joinGroup(null, null)", IllegalArgumentException.class,
                     () -> s.joinGroup(null, null));
            throwsIt("leaveGroup(null, null)", IllegalArgumentException.class,
                     () -> s.leaveGroup(null, null));

            // A SocketAddress that is not an InetSocketAddress. The API allows subclasses and
            // this one supports exactly one of them, which is what it has to say.
            SocketAddress strange = new SocketAddress() {
                private static final long serialVersionUID = 1L;
            };
            throwsIt("joinGroup(an unsupported address)", IllegalArgumentException.class,
                     () -> s.joinGroup(strange, null));

            // A real address that is not a multicast one. SocketException and not
            // IllegalArgumentException: the address is a usable address, it is the operation that
            // cannot be done with it.
            InetSocketAddress unicast = new InetSocketAddress("127.0.0.1", 0);
            throwsIt("joinGroup(a unicast address)", SocketException.class,
                     () -> s.joinGroup(unicast, null));
            throwsIt("leaveGroup(a unicast address)", SocketException.class,
                     () -> s.leaveGroup(unicast, null));
        } finally {
            s.close();
        }

        throwsIt("joinGroup after close", SocketException.class,
                 () -> s.joinGroup(new InetSocketAddress("224.0.0.1", 0), null));
        throwsIt("leaveGroup after close", SocketException.class,
                 () -> s.leaveGroup(new InetSocketAddress("224.0.0.1", 0), null));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("DatagramSocket options and multicast groups on "
                                   + System.getProperty("os.name") + " "
                                   + System.getProperty("os.arch"));

        optionen();
        gruppen();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
