import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * {@code AutoCloseable} where it was added late: {@code ExecutorService} (Java 19) and
 * {@code Inflater} (Java 25), on the real library.
 *
 * <h2>Why one line of interface was worth more than the method</h2>
 *
 * <p>{@code ExecutorService} did not extend {@code AutoCloseable} here, so no executor could be
 * used in a try-with-resources — and {@code close()} was reported missing on
 * {@code ForkJoinPool} as well, where it is not declared either. Adding the interface and its
 * default closed both: a pool, a scheduled pool, a virtual-thread executor and a
 * {@code ForkJoinPool} all gained it at once, by inheritance, with nothing written per class.
 *
 * <p>That is the half worth checking. A default method on an interface is reachable from every
 * implementor <em>in theory</em>; whether a particular class really answers it is a question about
 * this runtime, and three of the classes below are Android's rather than OpenJDK's.
 *
 * <h2>What the behaviour actually has to be</h2>
 *
 * <p>Measured on JDK 25 on 04.10.2026. {@code close()} is not {@code shutdown()}: it shuts down
 * <em>and waits</em>, so a task submitted before it must have finished by the time the
 * try-with-resources block is left. That is the property a caller is relying on when they write
 * the block, and an implementation that only called {@code shutdown()} would pass a test that
 * merely checked {@code isShutdown()}.
 *
 * <p>{@code Inflater.close()} is {@code end()} under the name the language needs. Calling it twice
 * is harmless, which matters because try-with-resources calls it on a resource a method body may
 * already have ended.
 */
public class CloseCheck {

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

    /** That an executor is AutoCloseable at all, and that closing waits. */
    static void einExecutor(String name, ExecutorService pool) {
        ok(name + " is AutoCloseable", pool instanceof AutoCloseable);

        final AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    done.incrementAndGet();
                }
            });
        }

        pool.close();

        // The whole point: close() waited. shutdown() alone would return here with the tasks
        // still running, and a caller who wrote try-with-resources would read their results
        // before they existed.
        eq(name + " close() waited for every task", done.get(), 4);
        ok(name + " is terminated after close()", pool.isTerminated());
        ok(name + " is shut down after close()", pool.isShutdown());

        // And again, on a pool that has already terminated. The default returns at once; an
        // implementation that shut down a second time would still be correct, but one that threw
        // would break every nested block.
        pool.close();
        ok(name + " close() twice is harmless", pool.isTerminated());
    }

    static void executoren() {
        einExecutor("newFixedThreadPool", Executors.newFixedThreadPool(2));
        einExecutor("newCachedThreadPool", Executors.newCachedThreadPool());
        einExecutor("newSingleThreadExecutor", Executors.newSingleThreadExecutor());
        einExecutor("newScheduledThreadPool", Executors.newScheduledThreadPool(2));

        // A ForkJoinPool of our own, not the common one — closing that would be closing the pool
        // every parallel stream in the program uses.
        einExecutor("ForkJoinPool", new ForkJoinPool(2));

        // And in the shape it was added for.
        final AtomicInteger ran = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    ran.incrementAndGet();
                }
            });
        }
        eq("try-with-resources ran the task", ran.get(), 1);
    }

    static void inflater() throws Exception {
        ok("Inflater is AutoCloseable", new Inflater() instanceof AutoCloseable);

        byte[] original = new byte[4096];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 251);         // compressible, but not a run of one byte
        }

        Deflater d = new Deflater();
        d.setInput(original);
        d.finish();
        byte[] packed = new byte[8192];
        int packedLength = d.deflate(packed);
        d.end();
        ok("the test data compressed", packedLength > 0 && packedLength < original.length);

        // The shape it was made AutoCloseable for.
        byte[] back = new byte[original.length];
        int backLength;
        try (Inflater in = new Inflater()) {
            in.setInput(packed, 0, packedLength);
            backLength = in.inflate(back);
        }
        eq("inflated length", backLength, original.length);

        boolean same = true;
        for (int i = 0; i < original.length; i++) {
            if (original[i] != back[i]) {
                same = false;
                break;
            }
        }
        ok("inflated bytes are the original", same);

        // close() twice, and close() after end(). Both happen in real code — a method that ends
        // its inflater explicitly and is then left through a try-with-resources.
        Inflater twice = new Inflater();
        twice.end();
        twice.close();
        twice.close();
        checked++;                                   // got here without throwing
    }

    /**
     * The classes that gained {@code close()} without a line being written in them.
     *
     * <p>Reported as well as asserted, because this is the list that says whether one interface
     * change really reached everywhere — and it is the sort of thing that looks obvious until a
     * class turns out to implement {@code ExecutorService} the long way round.
     */
    static void wasGeerbtWurde() {
        List<ExecutorService> alle = new ArrayList<ExecutorService>();
        alle.add(Executors.newFixedThreadPool(1));
        alle.add(Executors.newScheduledThreadPool(1));
        alle.add(Executors.newSingleThreadScheduledExecutor());
        alle.add(new ForkJoinPool(1));
        alle.add(Executors.newWorkStealingPool(1));

        for (ExecutorService pool : alle) {
            String name = pool.getClass().getSimpleName();
            ok(name + " inherits close()", pool instanceof AutoCloseable);
            pool.close();
            ok(name + " terminated", pool.isTerminated());
        }
        System.out.println("  close() reaches: " + alle.size() + " executor kinds");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("AutoCloseable on ExecutorService (19) and Inflater (25), on "
                                   + System.getProperty("os.name") + " "
                                   + System.getProperty("os.arch"));

        executoren();
        inflater();
        wasGeerbtWurde();

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
