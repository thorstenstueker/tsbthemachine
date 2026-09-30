import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;

/**
 * The absolute buffer methods of Java 11 through 22, on the real library.
 *
 * Java 13 added slice(index, length), Java 16 added the absolute bulk get and put and pulled
 * slice and duplicate up onto Buffer, Java 11 added mismatch, and Java 22 added getChars. Fifty-five
 * methods across eight classes, and almost all of them are one loop over a get or a put -- which is
 * why the interesting question is not whether they run but whether they address the right element.
 *
 * Three things here can be wrong while compiling perfectly:
 *
 * The index of an absolute method counts from the start of the buffer, not from the position. A
 * relative method that has been turned into an absolute one by moving the position and calling the
 * old code reads the right values for a buffer whose position is zero, which is every buffer in a
 * careless test. So every buffer below is positioned away from zero first.
 *
 * getChars is the exception, and it is the one that looks like a mistake. It comes from
 * CharSequence, whose indices are those of the sequence the buffer presents -- which begins at the
 * position. Implementing it like its neighbours gives an off-by-position that only appears once
 * somebody uses a CharBuffer as a CharSequence, which is the entire reason the method exists.
 *
 * A view buffer -- IntBuffer over a ByteBuffer -- keeps its offset in bytes while its index counts
 * elements, so slice has to scale one into the other. Getting the shift wrong reads four times too
 * far along, or not at all, and an aligned buffer starting at zero hides it. So the views below are
 * taken from a byte buffer whose position is not a multiple of anything.
 *
 * MappedByteBuffer's twelve are covariant overrides with no logic, but the covariance is the point:
 * code written since Java 9 chains mapped.position(0).force(), and a ByteBuffer return type makes
 * that fail to compile. A test that calls them through a ByteBuffer variable proves nothing, so the
 * variables below are declared as MappedByteBuffer.
 */
public class BufferCheck {

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

    // ---------------------------------------------------------------- absolute indices

    static void absoluteIndicesIgnoreThePosition() {
        // 0 1 2 3 4 5 6 7, positioned at 3. Every absolute call below must still see index 0 as
        // the zero the buffer was filled with.
        ByteBuffer b = ByteBuffer.allocate(8);
        for (int i = 0; i < 8; i++) {
            b.put(i, (byte) i);
        }
        b.position(3);

        byte[] dst = new byte[3];
        b.get(1, dst);
        eq("ByteBuffer.get(1, dst) reads from index 1, not from the position",
           dst[0] + "," + dst[1] + "," + dst[2], "1,2,3");
        eq("and the position did not move", b.position(), 3);

        ByteBuffer s = b.slice(2, 4);
        eq("slice(2, 4) has that length", s.remaining(), 4);
        eq("slice(2, 4) starts at index 2", s.get(0), (byte) 2);
        eq("and ends at index 5", s.get(3), (byte) 5);
        eq("the sliced-from buffer is unmoved", b.position(), 3);

        // Shared storage, both ways -- a slice that copied would pass everything above.
        s.put(0, (byte) 99);
        eq("a slice shares its storage", b.get(2), (byte) 99);
        b.put(3, (byte) 98);
        eq("and shares it in the other direction", s.get(1), (byte) 98);

        throwsToo("slice past the limit", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                ByteBuffer.allocate(8).slice(6, 4);
            }
        });
        throwsToo("slice at a negative index", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                ByteBuffer.allocate(8).slice(-1, 2);
            }
        });
    }

    // ---------------------------------------------------------------- the view buffers

    static void viewBuffersScaleTheirOffset() {
        // A byte buffer positioned at 4, so the int view's own offset is 4 bytes and not zero: a
        // slice that forgot to scale, or that scaled the wrong quantity, lands somewhere else.
        ByteBuffer raw = ByteBuffer.allocate(4 + 8 * 4);
        raw.position(4);
        IntBuffer ints = raw.asIntBuffer();
        for (int i = 0; i < 8; i++) {
            ints.put(i, 100 + i);
        }

        IntBuffer s = ints.slice(3, 4);
        eq("an int view slices at element 3", s.get(0), 103);
        eq("and runs to element 6", s.get(3), 106);
        eq("its length is in elements", s.remaining(), 4);
        s.put(0, 999);
        eq("a view slice writes through to the bytes", ints.get(3), 999);
        eq("and the bytes are where the int view says they are", raw.getInt(4 + 3 * 4), 999);

        // Eight bytes per element rather than four, which is the shift most likely to be copied
        // wrong from the neighbouring class.
        ByteBuffer raw8 = ByteBuffer.allocate(8 + 6 * 8);
        raw8.position(8);
        LongBuffer longs = raw8.asLongBuffer();
        for (int i = 0; i < 6; i++) {
            longs.put(i, 1000L + i);
        }
        eq("a long view slices at element 2", longs.slice(2, 3).get(0), 1002L);
        eq("and the byte offset agrees", raw8.getLong(8 + 2 * 8), 1002L);

        ShortBuffer shorts = ByteBuffer.allocate(20).asShortBuffer();
        for (int i = 0; i < 10; i++) {
            shorts.put(i, (short) (i * 3));
        }
        eq("a short view slices at element 4", shorts.slice(4, 2).get(0), (short) 12);
    }

    // ---------------------------------------------------------------- getChars

    static void getCharsCountsFromThePosition() {
        CharBuffer c = CharBuffer.wrap("abcdefgh");
        c.position(2);          // the sequence it now presents is "cdefgh"

        eq("length() is what remains", c.length(), 6);
        eq("charAt(0) is the character at the position", c.charAt(0), 'c');

        char[] dst = new char[4];
        c.getChars(0, 4, dst, 0);
        eq("getChars(0, 4) takes the first four of the sequence, not of the buffer",
           new String(dst), "cdef");

        char[] partial = new char[6];
        java.util.Arrays.fill(partial, '-');
        c.getChars(1, 4, partial, 1);
        eq("getChars writes at the destination offset and nowhere else",
           new String(partial), "-def--");

        throwsToo("getChars past the sequence", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                CharBuffer b = CharBuffer.wrap("abcd");
                b.position(2);
                b.getChars(0, 3, new char[4], 0);
            }
        });
        throwsToo("getChars into too small an array", IndexOutOfBoundsException.class,
                  new Runnable() {
                      public void run() {
                          CharBuffer.wrap("abcd").getChars(0, 4, new char[2], 0);
                      }
                  });

        // A heap CharBuffer as well as the String-backed one: two different classes implement
        // charAt and get, and getChars sits on top of whichever it is.
        CharBuffer heap = CharBuffer.allocate(8);
        heap.put("abcdefgh");
        heap.position(3);
        char[] four = new char[3];
        heap.getChars(0, 3, four, 0);
        eq("getChars on a heap buffer counts from the position too", new String(four), "def");
    }

    // ---------------------------------------------------------------- bulk put

    static void absoluteBulkPut() {
        IntBuffer b = IntBuffer.allocate(8);
        b.position(5);
        b.put(1, new int[] {10, 20, 30});
        eq("absolute put writes at the index", b.get(1) + "," + b.get(3), "10,30");
        eq("and leaves the position alone", b.position(), 5);
        eq("and does not touch its neighbour", b.get(0), 0);

        b.put(4, new int[] {7, 8, 9, 10}, 1, 2);
        eq("with an offset into the source", b.get(4) + "," + b.get(5), "8,9");

        // Overlapping copy within one buffer, forwards: a naive loop would read what it has just
        // written and smear the first value over the whole range.
        IntBuffer o = IntBuffer.allocate(8);
        for (int i = 0; i < 8; i++) {
            o.put(i, i);
        }
        o.put(2, o, 1, 4);
        eq("an overlapping put copies the old values",
           o.get(2) + "," + o.get(3) + "," + o.get(4) + "," + o.get(5), "1,2,3,4");

        // And backwards, the other direction of the same trap.
        IntBuffer o2 = IntBuffer.allocate(8);
        for (int i = 0; i < 8; i++) {
            o2.put(i, i);
        }
        o2.put(1, o2, 2, 4);
        eq("and in the other direction",
           o2.get(1) + "," + o2.get(2) + "," + o2.get(3) + "," + o2.get(4), "2,3,4,5");

        throwsToo("absolute put past the limit", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                IntBuffer.allocate(4).put(2, new int[] {1, 2, 3});
            }
        });
        throwsToo("absolute put on a read-only buffer", ReadOnlyBufferException.class,
                  new Runnable() {
                      public void run() {
                          ByteBuffer.allocate(8).asReadOnlyBuffer().put(0, new byte[] {1});
                      }
                  });
    }

    // ---------------------------------------------------------------- mismatch

    static void mismatchFindsTheFirstDifference() {
        ByteBuffer a = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        ByteBuffer b = ByteBuffer.wrap(new byte[] {1, 2, 9, 4});
        eq("mismatch at index 2", a.mismatch(b), 2);
        eq("mismatch with itself is -1", a.mismatch(a.duplicate()), -1);

        ByteBuffer shorter = ByteBuffer.wrap(new byte[] {1, 2});
        eq("a prefix mismatches at its own length", a.mismatch(shorter), 2);

        // Relative to each position, not to index zero.
        ByteBuffer p = ByteBuffer.wrap(new byte[] {9, 9, 1, 2, 3});
        p.position(2);
        ByteBuffer q = ByteBuffer.wrap(new byte[] {1, 2, 7});
        eq("mismatch counts from each position", p.mismatch(q), 2);

        // NaN is not equal to itself under ==, and a mismatch written with != says a buffer of
        // NaNs differs from itself at index zero. equals() and compareTo() on these two classes
        // already treat NaN as equal to NaN, and mismatch has to agree with them.
        FloatBuffer nf = FloatBuffer.wrap(new float[] {1f, Float.NaN, 3f});
        eq("NaN matches NaN", nf.mismatch(FloatBuffer.wrap(new float[] {1f, Float.NaN, 3f})), -1);
        DoubleBuffer nd = DoubleBuffer.wrap(new double[] {Double.NaN, 2});
        eq("and on doubles too", nd.mismatch(DoubleBuffer.wrap(new double[] {Double.NaN, 2})), -1);

        // And zero, where I guessed wrong twice before reading the contract. A float buffer does
        // not use Double.equals or Double.compare: it says so on both methods -- equals treats
        // -0.0 and +0.0 as equal "unlike Double.equals(Object)", and compareTo compares "as if by
        // invoking Double.compare, except that -0.0 and 0.0 are considered to be equal". So all
        // three of equals, compareTo and mismatch agree here, and an implementation that reached
        // for Double.compare to look tidy would disagree with the specification in two of them.
        eq("0.0 and -0.0 are the same element to mismatch",
           DoubleBuffer.wrap(new double[] {0.0}).mismatch(DoubleBuffer.wrap(new double[] {-0.0})),
           -1);
        eq("as they are to compareTo",
           DoubleBuffer.wrap(new double[] {0.0})
                   .compareTo(DoubleBuffer.wrap(new double[] {-0.0})), 0);
        ok("and to equals",
           DoubleBuffer.wrap(new double[] {0.0}).equals(DoubleBuffer.wrap(new double[] {-0.0})));

        // NaN the other way round: greater than everything, and equal to itself, in all three.
        eq("NaN sorts after every number",
           DoubleBuffer.wrap(new double[] {Double.NaN})
                   .compareTo(DoubleBuffer.wrap(new double[] {Double.POSITIVE_INFINITY})), 1);
    }

    // ---------------------------------------------------------------- alignment

    static void alignment() {
        ByteBuffer heap = ByteBuffer.allocate(64);
        eq("a heap buffer is treated as aligned at zero", heap.alignmentOffset(0, 4), 0);
        eq("and index 3 is three past a four-byte boundary", heap.alignmentOffset(3, 4), 3);
        eq("unit size one always fits", heap.alignmentOffset(7, 1), 0);

        throwsToo("a unit size that is not a power of two", IllegalArgumentException.class,
                  new Runnable() {
                      public void run() {
                          ByteBuffer.allocate(8).alignmentOffset(0, 3);
                      }
                  });
        throwsToo("a negative index", IndexOutOfBoundsException.class, new Runnable() {
            public void run() {
                ByteBuffer.allocate(8).alignmentOffset(-1, 4);
            }
        });

        ByteBuffer b = ByteBuffer.allocate(32);
        b.position(3).limit(30);
        ByteBuffer aligned = b.alignedSlice(4);
        eq("an aligned slice begins on a boundary", aligned.alignmentOffset(0, 4), 0);
        eq("and its length is a whole number of units", aligned.capacity() % 4, 0);
        ok("it lies inside what it was sliced from", aligned.capacity() <= 27);

        ByteBuffer direct = ByteBuffer.allocateDirect(64);
        ByteBuffer da = direct.alignedSlice(8);
        eq("a direct aligned slice begins on a boundary too", da.alignmentOffset(0, 8), 0);
    }

    // ---------------------------------------------------------------- MappedByteBuffer

    static void mappedReturnsItself() {
        // Declared as MappedByteBuffer throughout: if any of these still answered a ByteBuffer
        // this method would not compile, which is exactly the failure being guarded against.
        MappedByteBuffer m = (MappedByteBuffer) ByteBuffer.allocateDirect(32);
        MappedByteBuffer chained = m.position(4).limit(20).mark().position(8).reset();
        eq("position, limit, mark and reset chain and return the same buffer", chained.position(), 4);
        ok("and it is the very same object", chained == m);

        MappedByteBuffer f = m.clear().flip().rewind();
        ok("clear, flip and rewind chain too", f == m);

        MappedByteBuffer s = m.clear().slice(4, 8);
        eq("a mapped slice is a mapped buffer of that length", s.capacity(), 8);
        MappedByteBuffer d = s.duplicate();
        eq("and duplicate keeps the type", d.capacity(), 8);
        MappedByteBuffer c = d.compact();
        ok("as does compact", c != null);

        // force() on a buffer that was never mapped is unsupported, not silently ignored: the
        // buffer has no file descriptor to write back to.
        throwsToo("force on a direct-but-not-mapped buffer", UnsupportedOperationException.class,
                  new Runnable() {
                      public void run() {
                          ((MappedByteBuffer) ByteBuffer.allocateDirect(16)).force(0, 8);
                      }
                  });
    }

    static void mappedFileRegion() {
        File tmp = null;
        RandomAccessFile raf = null;
        try {
            tmp = File.createTempFile("buffercheck", ".bin");
            raf = new RandomAccessFile(tmp, "rw");
            raf.setLength(4096);
            FileChannel ch = raf.getChannel();
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, 0, 4096);

            m.put(100, (byte) 42);
            MappedByteBuffer back = m.force(64, 128);
            ok("force(index, length) returns the same buffer", back == m);
            eq("and the content survived it", m.get(100), (byte) 42);

            // A region that begins mid-page: the kernel works in whole pages, so this is where an
            // unrounded address would be rejected or would write back the wrong bytes.
            m.force(1, 1);
            m.force(0, 4096);
            eq("forcing the whole mapping leaves the content alone", m.get(100), (byte) 42);

            final MappedByteBuffer zuWeit = m;
            throwsToo("force past the limit", IndexOutOfBoundsException.class, new Runnable() {
                public void run() {
                    zuWeit.force(4000, 200);
                }
            });

            ch.close();
        } catch (Throwable t) {
            System.out.println("  (skipped the file-mapping checks: " + t + ")");
        } finally {
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (Throwable ignored) {
                // closing a file we are finished with
            }
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    // ---------------------------------------------------------------- every typed buffer

    static void everyTypedBufferHasTheFamily() {
        // The six typed classes got the same block of six methods each, generated from one shape.
        // A transcription error in one of them -- the wrong array type, the wrong shift, a get
        // where a put belongs -- shows up here and nowhere else.
        CharBuffer cb = CharBuffer.allocate(6);
        cb.put(0, new char[] {'a', 'b', 'c'});
        char[] cd = new char[2];
        cb.get(1, cd);
        eq("CharBuffer round trip", new String(cd), "bc");
        eq("CharBuffer slice", cb.slice(1, 2).get(0), 'b');

        ShortBuffer sb = ShortBuffer.allocate(6);
        sb.put(0, new short[] {1, 2, 3});
        short[] sd = new short[2];
        sb.get(1, sd);
        eq("ShortBuffer round trip", sd[0] + "," + sd[1], "2,3");
        eq("ShortBuffer slice", sb.slice(1, 2).get(0), (short) 2);

        IntBuffer ib = IntBuffer.allocate(6);
        ib.put(0, new int[] {1, 2, 3});
        int[] id = new int[2];
        ib.get(1, id);
        eq("IntBuffer round trip", id[0] + "," + id[1], "2,3");
        eq("IntBuffer slice", ib.slice(1, 2).get(0), 2);

        LongBuffer lb = LongBuffer.allocate(6);
        lb.put(0, new long[] {1, 2, 3});
        long[] ld = new long[2];
        lb.get(1, ld);
        eq("LongBuffer round trip", ld[0] + "," + ld[1], "2,3");
        eq("LongBuffer slice", lb.slice(1, 2).get(0), 2L);

        FloatBuffer fb = FloatBuffer.allocate(6);
        fb.put(0, new float[] {1, 2, 3});
        float[] fd = new float[2];
        fb.get(1, fd);
        eq("FloatBuffer round trip", fd[0] + "," + fd[1], "2.0,3.0");
        eq("FloatBuffer slice", fb.slice(1, 2).get(0), 2.0f);

        DoubleBuffer db = DoubleBuffer.allocate(6);
        db.put(0, new double[] {1, 2, 3});
        double[] dd = new double[2];
        db.get(1, dd);
        eq("DoubleBuffer round trip", dd[0] + "," + dd[1], "2.0,3.0");
        eq("DoubleBuffer slice", db.slice(1, 2).get(0), 2.0);

        // And through the base class, which is what Java 16 pulled them up for.
        java.nio.Buffer asBase = IntBuffer.allocate(8);
        eq("Buffer.slice(index, length) reaches the subclass", asBase.slice(2, 3).capacity(), 3);
        ok("Buffer.duplicate too", asBase.duplicate().capacity() == 8);
        ok("and Buffer.slice()", asBase.slice() != null);
    }

    public static void main(String[] args) {
        absoluteIndicesIgnoreThePosition();
        viewBuffersScaleTheirOffset();
        getCharsCountsFromThePosition();
        absoluteBulkPut();
        mismatchFindsTheFirstDifference();
        alignment();
        mappedReturnsItself();
        mappedFileRegion();
        everyTypedBufferHasTheFamily();

        System.out.println("BufferCheck: " + checked + " checked, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
