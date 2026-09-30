/*
 * Copyright (C) 2026 Thorsten Stüker
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/gpl-2.0.html>.
 */
package org.robovm.compiler.util;

import org.junit.Test;
import org.robovm.compiler.log.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * That {@link Executor#exec()} does not return before the process's output has been collected.
 *
 * <p>It used to. {@code exec()} started a daemon thread to copy the output into the caller's
 * stream and then waited only for the process, on the reasoning that the thread "will terminate as
 * soon as stream ends". It does — but not necessarily before {@code waitFor()} returns. Between
 * the two lies everything the process wrote that the pump has not yet copied, and for a command
 * that writes once and exits, that can be all of it.
 *
 * <p>{@link Executor#execCapture()} reads its buffer the moment {@code exec()} returns, so a
 * caller could be handed an empty or truncated string from a command that succeeded. Every user of
 * it was exposed, including the ones that locate clang and ask {@code xcode-select} where Xcode
 * is — and an empty Xcode path is a build failure that reads like a broken installation.
 *
 * <h2>Why the first test looks artificial</h2>
 *
 * <p>Because the honest alternative does not work. Two hundred repetitions of a command that exits
 * the instant it has written passed with the fix removed, on this machine, every time: the window
 * is real but narrow, and losing it needs the pump thread to be descheduled at the wrong moment —
 * a loaded machine, which a unit test is not.
 *
 * <p>So the test does not try to win a race. It makes the pump slow on purpose and then asserts
 * the property that was missing: when {@code exec()} returns, the output is there. With the wait
 * removed this fails outright rather than sometimes, which is what a regression test is for.
 */
public class ExecutorCaptureTest {

    private static boolean unix() {
        return new java.io.File("/bin/sh").canExecute();
    }

    /** A stream that takes its time, standing in for a pump thread that has not been scheduled. */
    private static final class LangsamerStrom extends OutputStream {
        final ByteArrayOutputStream ziel = new ByteArrayOutputStream();

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            schlafe();
            ziel.write(b, off, len);
        }

        @Override
        public void write(int b) {
            schlafe();
            ziel.write(b);
        }

        private void schlafe() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String toString() {
            return ziel.toString();
        }
    }

    @Test
    public void execWaitsForTheOutputToBeCollected() throws Exception {
        if (!unix()) {
            return;
        }
        LangsamerStrom langsam = new LangsamerStrom();
        long vorher = System.currentTimeMillis();
        new Executor(Logger.NULL_LOGGER, "/bin/sh")
                .args("-c", "printf /Applications/Xcode.app/Contents/Developer")
                .out(langsam)
                .exec();
        long dauer = System.currentTimeMillis() - vorher;

        assertEquals("the output must be complete by the time exec() returns",
                     "/Applications/Xcode.app/Contents/Developer", langsam.toString());
        assertTrue("and exec() must actually have waited, not merely been lucky: " + dauer + "ms",
                   dauer >= 300);
    }

    @Test
    public void captureReturnsTheWholeOutput() throws Exception {
        if (!unix()) {
            return;
        }
        // 64 KiB against a pipe buffer of the same order, so the pump goes round several times and
        // a wait that only covered the first read would truncate.
        String sechzigVier = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String got = new Executor(Logger.NULL_LOGGER, "/bin/sh")
                .args("-c", "i=0; while [ $i -lt 1024 ]; do printf '" + sechzigVier
                            + "'; i=$((i+1)); done")
                .execCapture();
        assertEquals(1024 * 64, got.length());
    }

    @Test
    public void repeatedShortCapturesAreStable() throws Exception {
        if (!unix()) {
            return;
        }
        // This one passed before the fix as well — see the class comment. It is kept because it
        // costs a second and would catch a wait that works only for a slow consumer.
        for (int i = 0; i < 200; i++) {
            String got = new Executor(Logger.NULL_LOGGER, "/bin/sh")
                    .args("-c", "printf ok").execCapture();
            assertEquals("run " + i, "ok", got);
        }
    }

    @Test
    public void aFailedCommandStillReportsItsExitCode() throws Exception {
        if (!unix()) {
            return;
        }
        // Waiting must not swallow the failure, and the failure must not skip the wait: a command
        // that writes something useful and then exits non-zero has to deliver both.
        try {
            new Executor(Logger.NULL_LOGGER, "/bin/sh").args("-c", "printf nope; exit 3")
                    .execCapture();
            fail("a command exiting 3 should have thrown");
        } catch (Executor.ExecuteException e) {
            assertEquals(3, e.getExitCode());
        }
    }
}
