/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.app.haptic;

import android.graphics.Rect;

/**
 * Lenovo pen haptic service ("zui_pen_haptic"), same interface as ZUI so the
 * stock Lenovo apps work. Implemented in system_server by the device's
 * DeviceKeyHandler jar.
 *
 * @hide
 */
interface IZuiPenHapticManager {
    boolean isHapticReady();
    boolean setHapticImpactParams(int waveFormId, int level, int repeatCount, int cutOffTime);
    boolean setHapticContinuousParams(int waveFormId, int level, int startFriction);
    boolean stopHaptic(int waveFormId);
    void setHapticSdkPackage(int pid);
    void setUseHaptic(boolean use);
    void setEraserRect(in List<Rect> eraserRects, in List<Rect> maskedRects);
}
