/*
 * Copyright (C) 2026 Thorsten Stueker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package java.lang.module;

/**
 * A module descriptor — as a type only.
 *
 * <p>No instance exists on tsbTheMachine: every class is in an unnamed module, and
 * {@link java.lang.Module#getDescriptor()} answers {@code null} for those, as it does on the JDK.
 * The class is here so that code which asks for the descriptor and then checks it for
 * {@code null} links (OpenJFX's {@code PlatformImpl} does exactly that before anything else).
 */
public final class ModuleDescriptor implements Comparable<ModuleDescriptor> {

    private final String name;
    private final boolean open;
    private final boolean automatic;

    private ModuleDescriptor(String name, boolean open, boolean automatic) {
        this.name = name;
        this.open = open;
        this.automatic = automatic;
    }

    public String name() {
        return name;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isAutomatic() {
        return automatic;
    }

    @Override
    public int compareTo(ModuleDescriptor that) {
        return name.compareTo(that.name);
    }

    @Override
    public String toString() {
        return "module " + name;
    }
}
