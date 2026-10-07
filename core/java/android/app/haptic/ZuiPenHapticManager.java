/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.app.haptic;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.List;

/**
 * Client of the Lenovo pen haptic service, API compatible with the ZUI class
 * the Lenovo apps (PenService, Lenovo Notes) are compiled against. Obtained
 * with {@code context.getSystemService("zui_pen_haptic")}. The service is
 * looked up lazily, calls fail soft while it is not there.
 *
 * <p>Third-party note apps call it too (Notein checks {@link #isHapticReady()}
 * on every pen touch). Being {@code @hide}, its members would be in the
 * blocked hidden API list and such an app crashes with NoSuchMethodError on
 * the first stroke, so the members an app can call are
 * {@link UnsupportedAppUsage}: the "unsupported" list, callable at any
 * target SDK, as on ZUI.
 *
 * @hide
 */
public class ZuiPenHapticManager {
    private static final String TAG = "ZuiPenHapticManager";

    /** Service name. */
    @UnsupportedAppUsage
    public static final String SERVICE = "zui_pen_haptic";

    private IZuiPenHapticManager mService;
    private boolean mSdkRegistered;

    @UnsupportedAppUsage
    public ZuiPenHapticManager(Context context) {
    }

    private IZuiPenHapticManager service() {
        if (mService == null || !mService.asBinder().isBinderAlive()) {
            IBinder b = ServiceManager.getService(SERVICE);
            mService = b != null ? IZuiPenHapticManager.Stub.asInterface(b) : null;
            if (mService != null && !mSdkRegistered) {
                mSdkRegistered = true;
                try {
                    mService.setHapticSdkPackage(Process.myPid());
                } catch (RemoteException e) {
                    Log.w(TAG, "setHapticSdkPackage", e);
                }
            }
        }
        return mService;
    }

    @UnsupportedAppUsage
    public boolean setHapticImpactParams(int waveFormId, int level, int repeatCount,
            int cutOffTime) {
        IZuiPenHapticManager s = service();
        if (s == null) return false;
        try {
            return s.setHapticImpactParams(waveFormId, level, repeatCount, cutOffTime);
        } catch (RemoteException e) {
            Log.w(TAG, "setHapticImpactParams", e);
            return false;
        }
    }

    @UnsupportedAppUsage
    public boolean setHapticContinuousParams(int waveFormId, int level, int startFriction) {
        IZuiPenHapticManager s = service();
        if (s == null) return false;
        try {
            return s.setHapticContinuousParams(waveFormId, level, startFriction);
        } catch (RemoteException e) {
            Log.w(TAG, "setHapticContinuousParams", e);
            return false;
        }
    }

    @UnsupportedAppUsage
    public boolean stopHaptic(int waveFormId) {
        IZuiPenHapticManager s = service();
        if (s == null) return false;
        try {
            return s.stopHaptic(waveFormId);
        } catch (RemoteException e) {
            Log.w(TAG, "stopHaptic", e);
            return false;
        }
    }

    @UnsupportedAppUsage
    public boolean isHapticReady() {
        IZuiPenHapticManager s = service();
        if (s == null) return false;
        try {
            return s.isHapticReady();
        } catch (RemoteException e) {
            Log.w(TAG, "isHapticReady", e);
            return false;
        }
    }

    @UnsupportedAppUsage
    public void setUseHaptic(boolean use) {
        IZuiPenHapticManager s = service();
        if (s == null) return;
        try {
            s.setUseHaptic(use);
        } catch (RemoteException e) {
            Log.w(TAG, "setUseHaptic", e);
        }
    }

    @UnsupportedAppUsage
    public void setEraserRect(List<Rect> eraserRects, List<Rect> maskedRects) {
        IZuiPenHapticManager s = service();
        if (s == null) return;
        try {
            s.setEraserRect(eraserRects, maskedRects);
        } catch (RemoteException e) {
            Log.w(TAG, "setEraserRect", e);
        }
    }
}
