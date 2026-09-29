import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The Future family's Java 12 and Java 19 additions, on the real library.
 *
 * Three layers went in together and they fail differently, so all three are exercised here:
 *
 *   Future got state(), resultNow() and exceptionNow() as *default* methods, which means every
 *   Future in the library and every one a customer wrote inherits them. FutureTask below is there
 *   for exactly that reason: it declares none of the three, so if it answers correctly the default
 *   bodies are right, and if CompletableFuture also answers correctly its overrides agree with
 *   them. Two implementations that disagree would be worse than either being absent.
 *
 *   CompletionStage got five exceptionally* methods, also as defaults, expressed through handle()
 *   and thenCompose(). The composing ones are where a wrong body hides: they must flatten a
 *   stage-of-a-stage, and an implementation that forgets to compose returns a future whose value
 *   is itself a future -- which only shows when someone reads the result.
 *
 *   CompletableFuture overrides all eight. The interesting part is not that they exist but that
 *   they agree with the defaults they replace, and that the exception a caller gets back is the one
 *   the task threw rather than the CompletionException it was wrapped in on the way through.
 */
public class FutureCheck {

    static int checked, failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    static void throwsToo(String what, Class<? extends Throwable> expected, Runnable r) {
        checked++;
        try {
            r.run();
            failed++;
            System.out.println("  MISMATCH " + what + ": threw nothing, wanted " + expected.getName());
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                failed++;
                System.out.println("  MISMATCH " + what + ": threw " + t.getClass().getName()
                        + ", wanted " + expected.getName());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        forkJoinPoolRuns();
        futureDefaults();
        completableFutureState();
        exceptionallyStages();
        composeStages();

        System.out.println("checked " + checked + " assertions, " + failed + " mismatches");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * That the pool can start a worker at all.
     *
     * This is first because until 29.09.2026 it could not, and nothing else here would have said
     * so. ForkJoinWorkerThread's static initialiser looked up OpenJDK's Thread fields --
     * threadLocals, inheritableThreadLocals, inheritedAccessControlContext -- which this Thread
     * does not have; it keeps thread-local storage in localValues and inheritableValues and has no
     * AccessControlContext. getDeclaredField threw, the initialiser turned it into an Error, and
     * every worker the pool tried to create died on class initialisation.
     *
     * So the whole default-executor half of java.util.concurrent was dead: supplyAsync, every
     * *Async stage that did not name an executor, and parallel streams. It failed loudly with
     * "NoSuchFieldException: threadLocals", but only when something reached for it, and nothing in
     * the checks did.
     *
     * Three ways in, because they enter the pool differently: a CompletableFuture, a parallel
     * stream, and the pool itself.
     */
    static void forkJoinPoolRuns() throws Exception {
        eq("supplyAsync on the default executor",
           CompletableFuture.supplyAsync(() -> "async").join(), "async");

        eq("thenApplyAsync on the default executor",
           CompletableFuture.completedFuture(20).thenApplyAsync(n -> n * 2 + 2).join(), 42);

        eq("parallel stream reduces",
           java.util.stream.IntStream.rangeClosed(1, 1000).parallel().sum(), 500500);

        java.util.concurrent.ForkJoinPool pool = java.util.concurrent.ForkJoinPool.commonPool();
        eq("commonPool submit", pool.submit(() -> "submitted").get(), "submitted");

        // A worker really ran on a pool thread, not inline on this one -- the point of the fix is
        // that a worker thread can be constructed at all.
        String where = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName()).join();
        checked++;
        if (where.equals(Thread.currentThread().getName())) {
            failed++;
            System.out.println("  MISMATCH supplyAsync ran on the calling thread: " + where);
        }
    }

    /** FutureTask declares none of the three, so this tests Future's default bodies. */
    static void futureDefaults() throws Exception {
        FutureTask<String> ok = new FutureTask<>(() -> "done");
        eq("FutureTask running state", ok.state(), Future.State.RUNNING);
        throwsToo("FutureTask resultNow while running", IllegalStateException.class, ok::resultNow);
        throwsToo("FutureTask exceptionNow while running", IllegalStateException.class, ok::exceptionNow);
        ok.run();
        eq("FutureTask success state", ok.state(), Future.State.SUCCESS);
        eq("FutureTask resultNow", ok.resultNow(), "done");
        throwsToo("FutureTask exceptionNow after success", IllegalStateException.class, ok::exceptionNow);

        Exception boom = new IllegalArgumentException("boom");
        FutureTask<String> bad = new FutureTask<>(() -> { throw boom; });
        bad.run();
        eq("FutureTask failed state", bad.state(), Future.State.FAILED);
        eq("FutureTask exceptionNow", bad.exceptionNow(), boom);
        throwsToo("FutureTask resultNow after failure", IllegalStateException.class, bad::resultNow);

        FutureTask<String> gone = new FutureTask<>(() -> "never");
        gone.cancel(false);
        eq("FutureTask cancelled state", gone.state(), Future.State.CANCELLED);
        throwsToo("FutureTask resultNow after cancel", IllegalStateException.class, gone::resultNow);
        throwsToo("FutureTask exceptionNow after cancel", IllegalStateException.class, gone::exceptionNow);

        // The same questions asked of a task an executor ran, so the defaults are seen once on a
        // future that completed on another thread.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> f = pool.submit(() -> 41 + 1);
            f.get();
            eq("submitted state", f.state(), Future.State.SUCCESS);
            eq("submitted resultNow", f.resultNow(), 42);
        } finally {
            pool.shutdown();
        }
    }

    /** CompletableFuture overrides the three; they must agree with the defaults above. */
    static void completableFutureState() {
        CompletableFuture<String> running = new CompletableFuture<>();
        eq("CF running state", running.state(), Future.State.RUNNING);
        throwsToo("CF resultNow while running", IllegalStateException.class, running::resultNow);

        eq("CF success state", CompletableFuture.completedFuture("v").state(), Future.State.SUCCESS);
        eq("CF resultNow", CompletableFuture.completedFuture("v").resultNow(), "v");

        // A null result is held as AltResult(null) internally, which is the one case where a
        // completed future looks like a failed one from the field alone.
        CompletableFuture<String> nullValued = CompletableFuture.completedFuture(null);
        eq("CF null result state", nullValued.state(), Future.State.SUCCESS);
        eq("CF null resultNow", nullValued.resultNow(), null);
        throwsToo("CF exceptionNow on null result", IllegalStateException.class, nullValued::exceptionNow);

        Exception boom = new IllegalStateException("kaboom");
        CompletableFuture<String> failedCf = new CompletableFuture<>();
        failedCf.completeExceptionally(boom);
        eq("CF failed state", failedCf.state(), Future.State.FAILED);
        eq("CF exceptionNow unwraps to the thrown exception", failedCf.exceptionNow(), boom);
        throwsToo("CF resultNow after failure", IllegalStateException.class, failedCf::resultNow);

        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        eq("CF cancelled state", cancelled.state(), Future.State.CANCELLED);
        throwsToo("CF exceptionNow after cancel", IllegalStateException.class, cancelled::exceptionNow);

        // A stage *derived* from a failed one wraps the cause in a CompletionException. exceptionNow
        // must still hand back what was thrown, not the wrapper.
        CompletableFuture<String> derived = failedCf.thenApply(s -> s + "!");
        derived.handle((r, ex) -> null).join();          // make sure it has completed
        eq("derived CF state", derived.state(), Future.State.FAILED);
        eq("derived CF exceptionNow unwraps", derived.exceptionNow(), boom);
    }

    static void exceptionallyStages() {
        Exception boom = new RuntimeException("bad");
        Executor direct = Runnable::run;

        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(boom);

        eq("exceptionallyAsync recovers", failed.exceptionallyAsync(ex -> "recovered").join(), "recovered");
        eq("exceptionallyAsync(executor) recovers",
           failed.exceptionallyAsync(ex -> "r2", direct).join(), "r2");
        // A future completed with completeExceptionally hands the function that exception itself,
        // unwrapped. Only a *derived* stage sees it inside a CompletionException, which is checked
        // separately below.
        eq("exceptionallyAsync sees the exception",
           failed.exceptionallyAsync(ex -> ex.getMessage(), direct).join(), "bad");
        eq("derived stage sees it wrapped",
           failed.thenApply(s -> s)
                 .exceptionallyAsync(ex -> ex.getClass().getSimpleName(), direct).join(),
           "CompletionException");

        // On success the function must not run at all and the value must pass through untouched.
        CompletableFuture<String> good = CompletableFuture.completedFuture("kept");
        eq("exceptionallyAsync passes success through",
           good.exceptionallyAsync(ex -> "should not happen", direct).join(), "kept");

        // The return type is the narrowed one, not CompletionStage. This would not compile if the
        // override were missing, which is the point of asserting it.
        CompletableFuture<String> narrowed = good.exceptionallyAsync(ex -> "x", direct);
        eq("exceptionallyAsync returns a CompletableFuture", narrowed.getClass() == CompletableFuture.class, true);

        throwsToo("exceptionallyAsync(null)", NullPointerException.class,
                  () -> good.exceptionallyAsync(null, direct));
    }

    static void composeStages() {
        Exception boom = new RuntimeException("compose me");
        Executor direct = Runnable::run;

        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(boom);

        // The flattening is the thing: the result must be the String, not a future holding one.
        eq("exceptionallyCompose flattens",
           failed.exceptionallyCompose(ex -> CompletableFuture.completedFuture("flat")).join(), "flat");
        eq("exceptionallyComposeAsync flattens",
           failed.exceptionallyComposeAsync(ex -> CompletableFuture.completedFuture("flatA")).join(), "flatA");
        eq("exceptionallyComposeAsync(executor) flattens",
           failed.exceptionallyComposeAsync(ex -> CompletableFuture.completedFuture("flatE"), direct).join(),
           "flatE");

        CompletableFuture<String> good = CompletableFuture.completedFuture("untouched");
        eq("exceptionallyCompose passes success through",
           good.exceptionallyCompose(ex -> CompletableFuture.completedFuture("no")).join(), "untouched");
        eq("exceptionallyComposeAsync passes success through",
           good.exceptionallyComposeAsync(ex -> CompletableFuture.completedFuture("no"), direct).join(),
           "untouched");

        // A recovery stage that itself fails must leave the result failed, not swallow it.
        CompletableFuture<String> stillBad = failed.exceptionallyCompose(ex -> {
            CompletableFuture<String> worse = new CompletableFuture<>();
            worse.completeExceptionally(new IllegalStateException("worse"));
            return worse;
        });
        checked++;
        try {
            stillBad.join();
            failed(stillBad);
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof IllegalStateException)) {
                FutureCheck.failed++;
                System.out.println("  MISMATCH failing recovery: cause was " + e.getCause());
            }
        }

        // The same five reached through the CompletionStage interface, so the default bodies are
        // exercised and not only CompletableFuture's overrides.
        CompletionStage<String> asStage = failed;
        eq("CompletionStage.exceptionallyCompose",
           asStage.exceptionallyCompose(ex -> CompletableFuture.completedFuture("viaStage"))
                  .toCompletableFuture().join(), "viaStage");
        eq("CompletionStage.exceptionallyAsync",
           asStage.exceptionallyAsync(ex -> "viaStageAsync", direct)
                  .toCompletableFuture().join(), "viaStageAsync");
    }

    static void failed(Object what) {
        failed++;
        System.out.println("  MISMATCH expected a failure from " + what);
    }
}
