/*
 * Copyright (C) 2026 Thorsten Stueker
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
package org.robovm.compiler.target.androidlib;

import org.robovm.compiler.config.Arch;
import org.robovm.compiler.config.Config;
import org.robovm.compiler.config.OS;
import org.robovm.compiler.launcher.LaunchParameters;
import org.robovm.compiler.launcher.Launcher;
import org.robovm.compiler.target.AbstractTarget;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builds a shared library for Android rather than an executable: the artefact an APK carries in
 * {@code lib/arm64-v8a/} and loads with {@code System.loadLibrary}.
 *
 * <p>The Android counterpart of {@link org.robovm.compiler.target.framework.FrameworkTarget}, and
 * deliberately much smaller. A framework carries a bundle, an Info.plist, a code signature and an
 * xcframework wrapper; an APK wants one {@code .so} in a directory named after the ABI, and nothing
 * else.
 *
 * <p>Two VMs are in play once such a library is loaded. The APK's own code runs on ART, which calls
 * {@code JNI_OnLoad} here; the Java compiled into this library runs on ours, started through the
 * {@code JNI_CreateJavaVM} this library exports. Keeping both reachable is why {@code JNI_*} is
 * exported rather than one named entry point.
 */
public class AndroidLibTarget extends AbstractTarget {
    public static final String TYPE = "androidlib";

    private OS os;
    private Arch arch;

    public AndroidLibTarget() {
    }

    public String getType() {
        return TYPE;
    }

    public OS getOs() {
        return os;
    }

    public Arch getArch() {
        return arch;
    }

    @Override
    public List<Arch> getDefaultArchs() {
        return Collections.singletonList(Arch.arm64);
    }

    public void init(Config config) {
        super.init(config);
        // Android is never the host, so there is nothing sensible to default to and naming it is
        // not a burden: this target only makes sense for one OS.
        os = OS.android;
        arch = config.getArch();
        if (arch == null) {
            arch = Arch.arm64;
        }
    }

    /**
     * {@code lib<name>.so} -- the name {@code System.loadLibrary("<name>")} looks for. The build
     * itself produces the plain executable name; only what gets installed is renamed, so the
     * intermediate stays where the rest of AbstractTarget expects it.
     */
    public String getLibraryFileName() {
        return "lib" + config.getExecutableName() + ".so";
    }

    @Override
    public void install() throws IOException {
        config.getLogger().info("Installing %s to %s", getLibraryFileName(), config.getInstallDir());
        doInstall(config.getInstallDir(), getLibraryFileName(), config.getInstallDir());
    }

    @Override
    protected List<String> getTargetCcArgs() {
        // -soname rather than the file name alone: the dynamic linker records this string in the
        // APK's other libraries, and it has to be the name the file will carry once installed.
        return Arrays.asList(
                "--target=" + config.getClangTriple(),
                "-shared",
                "-Wl,-soname," + getLibraryFileName());
    }

    @Override
    protected List<String> getTargetLibs() {
        // --whole-archive, or nothing pulls it in: JNI_OnLoad is called by ART and referenced by
        // no code in this library, so the linker would drop the archive member unread and ART
        // would refuse the library with an UnsatisfiedLinkError that says nothing about why.
        return Arrays.asList("-Wl,--whole-archive", "-lrobovm-androidsupport", "-Wl,--no-whole-archive");
    }

    @Override
    protected List<String> getTargetExportedSymbols() {
        // JNI_CreateJavaVM for whoever starts this VM, JNI_OnLoad for ART when the APK loads the
        // library. AbstractTarget adds JNI_OnLoad_* itself.
        return Arrays.asList("JNI_*");
    }

    @Override
    protected Launcher createLauncher(LaunchParameters launchParameters) throws IOException {
        // A shared library has no entry point to launch. Running what is in it means loading it
        // from an APK, which is the stub's job and not this compiler's.
        throw new UnsupportedOperationException("An Android shared library cannot be launched directly");
    }

    @Override
    public LaunchParameters createLaunchParameters() {
        throw new UnsupportedOperationException("An Android shared library cannot be launched directly");
    }
}
