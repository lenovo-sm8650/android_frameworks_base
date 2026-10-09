/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.os.Handler;
import android.view.Display;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Process-local handwriting state shared by IMMS and device input extensions in system_server.
 * Contains no input text and exposes no Binder or app-facing interface.
 *
 * @hide
 */
public final class StylusHandwritingState {
    private static final Object sLock = new Object();
    private static final ArrayList<Listener> sListeners = new ArrayList<>();
    private static String sImePackage;
    private static int sDisplayId = Display.INVALID_DISPLAY;

    private StylusHandwritingState() {}

    /** The callback runs on the supplied handler and should read the latest state. */
    public static void addListener(Handler handler, Runnable callback) {
        Objects.requireNonNull(handler);
        Objects.requireNonNull(callback);
        synchronized (sLock) {
            sListeners.add(new Listener(handler, callback));
            handler.post(callback);
        }
    }

    public static String getActiveImePackage(int displayId) {
        synchronized (sLock) {
            return sDisplayId == displayId ? sImePackage : null;
        }
    }

    public static void start(String imePackage, int displayId) {
        Objects.requireNonNull(imePackage);
        synchronized (sLock) {
            if (displayId == sDisplayId && imePackage.equals(sImePackage)) return;
            sImePackage = imePackage;
            sDisplayId = displayId;
            notifyListenersLocked();
        }
    }

    public static void finish() {
        synchronized (sLock) {
            if (sImePackage == null) return;
            sImePackage = null;
            sDisplayId = Display.INVALID_DISPLAY;
            notifyListenersLocked();
        }
    }

    private static void notifyListenersLocked() {
        // Post only: device code must never run while IMMS holds ImfLock.
        for (Listener listener : sListeners) listener.handler.post(listener.callback);
    }

    private record Listener(Handler handler, Runnable callback) {}
}
