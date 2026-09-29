/*
 * Copyright (C) 2026 Thorsten Stueker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * What frameworksupport.m is for an iOS framework, this is for an Android shared library -- and
 * much smaller, because there is no Objective-C runtime to hand objects to and no bundle to
 * instantiate a root class from.
 *
 * Two virtual machines are in the process once an APK loads this library. ART runs the APK's own
 * code and calls JNI_OnLoad below; ours runs the Java compiled into this library and is started
 * through the JNI_CreateJavaVM this library exports. The one thing that must happen at load time
 * is answering ART with a JNI version -- a library that does not is refused outright, and the
 * failure arrives as an UnsatisfiedLinkError with nothing in it about why.
 *
 * Starting our own VM is deliberately *not* done here. It would run on whichever thread called
 * System.loadLibrary, usually the main thread during Activity creation, and take the whole startup
 * cost before anything is on screen. The stub decides when, and calls JNI_CreateJavaVM itself.
 */
#include <jni.h>

/* ART's VM, not ours. Kept so the stub can reach back into the APK's own classes -- the crossing
 * the other way, which A3b needs one of per frame. */
static JavaVM* hostVm = NULL;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    hostVm = vm;
    /* 1.4 and not 1.6: the return value states which JNI version this library needs of its host,
     * and core/include/jni.h stops at 1.4 -- that is what this VM actually implements. ART accepts
     * 1.2, 1.4 and 1.6, so claiming more would buy nothing and would be a claim we cannot keep. */
    return JNI_VERSION_1_4;
}

/* The VM that loaded this library, or NULL when it was not loaded by one -- dlopen from a plain
 * native process, as the A3b load test does, never calls JNI_OnLoad. */
JavaVM* rvmAndroidGetHostVM(void) {
    return hostVm;
}
