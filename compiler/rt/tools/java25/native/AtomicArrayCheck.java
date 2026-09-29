import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The memory-order family on the three atomic arrays, on the real library.
 *
 * These thirteen methods per class -- getPlain, setPlain, getOpaque, setOpaque, getAcquire,
 * setRelease, the three compareAndExchange variants and the four weakCompareAndSet variants -- were
 * added because sun.misc.Unsafe here offers volatile, ordered and compare-and-swap and nothing
 * between them, so each is implemented with a *stronger* ordering than its name asks for. The
 * specification states a minimum per mode and providing more cannot break a caller, so the
 * interesting question is not the ordering, which cannot be observed from a single thread anyway.
 * It is whether the *values* are right.
 *
 * compareAndExchange is where that can go wrong, and it is the only one of the thirteen with real
 * logic in it. Unsafe reports whether a swap happened, not what stood in the way, so the witness
 * has to be read separately -- and the contract is exact about which value comes back: the expected
 * value on success, and the value that blocked it on failure. An implementation that returns the
 * value it read first, or that reports the new value, passes a compile and fails here.
 *
 * Index checking is the other thing worth pinning. Every one of these takes an index, and an
 * out-of-range one must throw IndexOutOfBoundsException rather than reading whatever lies past the
 * array -- a wrong answer that a test written only for the happy path would never see.
 */
public class AtomicArrayCheck {

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

    public static void main(String[] args) {
        ints();
        longs();
        refs();

        System.out.println("checked " + checked + " assertions, " + failed + " mismatches");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void ints() {
        AtomicIntegerArray a = new AtomicIntegerArray(new int[] {10, 20, 30});

        // Reads: every mode must see what an ordinary set wrote.
        eq("int getPlain", a.getPlain(0), 10);
        eq("int getOpaque", a.getOpaque(1), 20);
        eq("int getAcquire", a.getAcquire(2), 30);

        // Writes: every mode must be visible to an ordinary get.
        a.setPlain(0, 11);
        eq("int setPlain", a.get(0), 11);
        a.setOpaque(1, 21);
        eq("int setOpaque", a.get(1), 21);
        a.setRelease(2, 31);
        eq("int setRelease", a.get(2), 31);

        // compareAndExchange: the witness on the way out is the contract.
        eq("int cae success returns expected", a.compareAndExchange(0, 11, 12), 11);
        eq("int cae success stored", a.get(0), 12);
        eq("int cae failure returns witness", a.compareAndExchange(0, 999, 13), 12);
        eq("int cae failure left it alone", a.get(0), 12);
        eq("int caeAcquire success", a.compareAndExchangeAcquire(0, 12, 14), 12);
        eq("int caeRelease failure", a.compareAndExchangeRelease(0, 999, 15), 14);
        eq("int caeRelease left it alone", a.get(0), 14);

        // weakCompareAndSet: allowed to fail spuriously in principle, but ours delegates to
        // compareAndSet, so a mismatch here means the delegation is wrong, not a spurious failure.
        eq("int wCASPlain", a.weakCompareAndSetPlain(1, 21, 22), true);
        eq("int wCASPlain stored", a.get(1), 22);
        eq("int wCASVolatile", a.weakCompareAndSetVolatile(1, 22, 23), true);
        eq("int wCASAcquire", a.weakCompareAndSetAcquire(1, 23, 24), true);
        eq("int wCASRelease", a.weakCompareAndSetRelease(1, 24, 25), true);
        eq("int wCAS chain stored", a.get(1), 25);
        eq("int wCAS wrong expectation fails", a.weakCompareAndSetPlain(1, 999, 26), false);
        eq("int wCAS failure left it alone", a.get(1), 25);

        // Indices.
        throwsToo("int getPlain(-1)", IndexOutOfBoundsException.class, () -> a.getPlain(-1));
        throwsToo("int setRelease(3)", IndexOutOfBoundsException.class, () -> a.setRelease(3, 0));
        throwsToo("int cae(3)", IndexOutOfBoundsException.class, () -> a.compareAndExchange(3, 0, 0));
        throwsToo("int wCASAcquire(-1)", IndexOutOfBoundsException.class,
                () -> a.weakCompareAndSetAcquire(-1, 0, 0));
    }

    static void longs() {
        // Values past 2^32, because a long array that quietly lost its upper half would still pass
        // every assertion made with small numbers.
        long big = 1L << 40;
        AtomicLongArray a = new AtomicLongArray(new long[] {big, big + 1, big + 2});

        eq("long getPlain", a.getPlain(0), big);
        eq("long getOpaque", a.getOpaque(1), big + 1);
        eq("long getAcquire", a.getAcquire(2), big + 2);

        a.setPlain(0, big + 10);
        eq("long setPlain", a.get(0), big + 10);
        a.setOpaque(1, big + 11);
        eq("long setOpaque", a.get(1), big + 11);
        a.setRelease(2, big + 12);
        eq("long setRelease", a.get(2), big + 12);

        eq("long cae success returns expected", a.compareAndExchange(0, big + 10, big + 20), big + 10);
        eq("long cae success stored", a.get(0), big + 20);
        eq("long cae failure returns witness", a.compareAndExchange(0, 0L, big + 30), big + 20);
        eq("long cae failure left it alone", a.get(0), big + 20);
        eq("long caeAcquire success", a.compareAndExchangeAcquire(0, big + 20, big + 21), big + 20);
        eq("long caeRelease failure", a.compareAndExchangeRelease(0, 0L, big + 22), big + 21);

        eq("long wCASPlain", a.weakCompareAndSetPlain(1, big + 11, big + 13), true);
        eq("long wCASVolatile", a.weakCompareAndSetVolatile(1, big + 13, big + 14), true);
        eq("long wCASAcquire", a.weakCompareAndSetAcquire(1, big + 14, big + 15), true);
        eq("long wCASRelease", a.weakCompareAndSetRelease(1, big + 15, big + 16), true);
        eq("long wCAS chain stored", a.get(1), big + 16);
        eq("long wCAS wrong expectation fails", a.weakCompareAndSetPlain(1, 0L, 0L), false);

        throwsToo("long getOpaque(3)", IndexOutOfBoundsException.class, () -> a.getOpaque(3));
        throwsToo("long cae(-1)", IndexOutOfBoundsException.class, () -> a.compareAndExchange(-1, 0, 0));
    }

    static void refs() {
        // Two equal-but-distinct strings on purpose: these compare by reference, so an
        // implementation that reached for equals() would pass with interned literals and be wrong.
        String x = new String("x");
        String sameText = new String("x");
        String y = "y";
        AtomicReferenceArray<String> a = new AtomicReferenceArray<>(new String[] {x, y, null});

        eq("ref getPlain", a.getPlain(0), "x");
        eq("ref getOpaque", a.getOpaque(1), "y");
        eq("ref getAcquire is null", a.getAcquire(2), "null");

        a.setPlain(2, "z");
        eq("ref setPlain", a.get(2), "z");
        a.setOpaque(2, null);
        eq("ref setOpaque null", a.get(2), "null");
        a.setRelease(2, "w");
        eq("ref setRelease", a.get(2), "w");

        checked++;
        if (a.compareAndExchange(0, sameText, "other") != x) {
            // Equal text, different object: identity must decide, so this must fail and hand back x.
            failed++;
            System.out.println("  MISMATCH ref cae compared by equals, not by reference");
        }
        eq("ref cae by equals left it alone", a.get(0), "x");

        checked++;
        if (a.compareAndExchange(0, x, "swapped") != x) {
            failed++;
            System.out.println("  MISMATCH ref cae success did not return the expected reference");
        }
        eq("ref cae success stored", a.get(0), "swapped");

        eq("ref caeAcquire failure returns witness", a.compareAndExchangeAcquire(0, "nope", "no"), "swapped");
        eq("ref caeRelease success", a.compareAndExchangeRelease(1, y, "y2"), "y");
        eq("ref caeRelease stored", a.get(1), "y2");

        eq("ref wCASPlain", a.weakCompareAndSetPlain(1, "y2", "y3"), true);
        eq("ref wCASVolatile", a.weakCompareAndSetVolatile(1, "y3", "y4"), true);
        eq("ref wCASAcquire", a.weakCompareAndSetAcquire(1, "y4", "y5"), true);
        eq("ref wCASRelease", a.weakCompareAndSetRelease(1, "y5", null), true);
        eq("ref wCAS chain stored null", a.get(1), "null");
        eq("ref wCAS from null", a.weakCompareAndSetPlain(1, null, "back"), true);
        eq("ref wCAS from null stored", a.get(1), "back");

        throwsToo("ref getAcquire(3)", IndexOutOfBoundsException.class, () -> a.getAcquire(3));
        throwsToo("ref setOpaque(-1)", IndexOutOfBoundsException.class, () -> a.setOpaque(-1, null));
    }
}
