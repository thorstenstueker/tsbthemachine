/*
 * Copyright (C) 2012 RoboVM AB
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
package org.robovm.compiler.config;

import org.robovm.llvm.Target;

/**
 * @author niklas
 *
 */
public enum OS {
    // minVersion is appended to the llvm name by Config.getTriple(): cpuArch-vendor-llvmName+minVersion.
    // Linux carried "linux" in that field and so produced `aarch64-unknown-linuxlinux`, which is not a
    // triple at all. It never showed because every Linux and Android measurement so far went through
    // CMake rather than through this compiler path. Emptied 28.09.2026 (tsb); the only other readers of
    // getMinVersion() are actool and ibtool, both iOS-only.
    linux("linux",  "unknown", ""),
    // Android is Linux on Bionic, and the triple the NDK expects is aarch64-none-linux-android26.
    // The API level lives here because it is part of the triple, not a separate compiler flag.
    android("linux-android", "none", "26"),
    macosx("macosx", "apple", "10.9"),
    ios("ios", "apple", "8.0");
    
    public enum Family {linux, darwin}

    private final String llvmName;
    private final String vendor;
    private final String minVersion;

    private OS(String llvmName, String vendor, String minVersion) {
        this.llvmName = llvmName;
        this.vendor = vendor;
        this.minVersion = minVersion;
    }

    public String getLlvmName() {
        return llvmName;
    }

    public String getVendor() {
        return vendor;
    }

    public String getMinVersion() {
        return minVersion;
    }
    
    public Family getFamily() {
        return (this == linux || this == android) ? Family.linux : Family.darwin;
    }

    // getDefaultOS() deliberately has no android branch: Android is a target, never the host.
    public static OS getDefaultOS() {
        String hostTriple = Target.getHostTriple();
        if (hostTriple.contains("linux")) {
            return OS.linux;
        }
        if (hostTriple.contains("darwin") || hostTriple.contains("apple")) {
            return OS.macosx;
        }
        throw new IllegalArgumentException("Unrecognized OS in host triple: " + hostTriple);
    }
}
