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
package java.lang;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The module of a class — on tsbTheMachine always the <em>unnamed module</em> of its class loader.
 *
 * <p>An ahead-of-time compiled program has no module layer: everything was linked from a class
 * path. That is exactly the situation of a JDK program started with {@code -cp}, where every class
 * is in the unnamed module of its loader, which reads every module and opens and exports every
 * package. This class gives that answer, and gives it consistently, so code written for the
 * module system (OpenJFX checks {@code isNamed()}, {@code isOpen(..)} and calls
 * {@code addOpens(..)} before reflecting) takes its class-path branch instead of failing with a
 * {@code NoSuchMethodError}.
 *
 * <p>{@code getDescriptor()} is {@code null}, as it is for an unnamed module on the JDK.
 * {@code getLayer()} is left out: without {@code ModuleLayer} it could only be declared with a
 * wrong type, and a wrong type is a {@code NoSuchMethodError} at the caller all the same.
 */
public final class Module {

    private static final Module BOOT = new Module(null);
    private static final WeakHashMap<ClassLoader, Module> UNNAMED = new WeakHashMap<ClassLoader, Module>();

    private final ClassLoader loader;

    private Module(ClassLoader loader) {
        this.loader = loader;
    }

    /** The unnamed module of {@code loader}; one instance per loader, as on the JDK. */
    static Module unnamedOf(ClassLoader loader) {
        if (loader == null) {
            return BOOT;
        }
        synchronized (UNNAMED) {
            Module m = UNNAMED.get(loader);
            if (m == null) {
                m = new Module(loader);
                UNNAMED.put(loader, m);
            }
            return m;
        }
    }

    public boolean isNamed() {
        return false;
    }

    public String getName() {
        return null;
    }

    public ClassLoader getClassLoader() {
        return loader;
    }

    /** Always {@code null}: an unnamed module has no descriptor. */
    public java.lang.module.ModuleDescriptor getDescriptor() {
        return null;
    }

    public boolean canRead(Module other) {
        return true;
    }

    public Module addReads(Module other) {
        return this;
    }

    public boolean isExported(String pn, Module other) {
        return true;
    }

    public boolean isExported(String pn) {
        return true;
    }

    public boolean isOpen(String pn, Module other) {
        return true;
    }

    public boolean isOpen(String pn) {
        return true;
    }

    public Module addExports(String pn, Module other) {
        return this;
    }

    public Module addOpens(String pn, Module other) {
        return this;
    }

    public Module addUses(Class<?> service) {
        return this;
    }

    public boolean canUse(Class<?> service) {
        return true;
    }

    /** The packages are not known without a layer; an empty set, never {@code null}. */
    public Set<String> getPackages() {
        return Collections.emptySet();
    }

    public InputStream getResourceAsStream(String name) throws IOException {
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
        return cl.getResourceAsStream(name);
    }

    @Override
    public String toString() {
        return "unnamed module @" + Integer.toHexString(System.identityHashCode(this));
    }
}
