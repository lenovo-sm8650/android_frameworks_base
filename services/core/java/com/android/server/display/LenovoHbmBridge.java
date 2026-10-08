/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.display.config.HighBrightnessModeData;

/**
 * The high brightness mode of the panel of the Lenovo Yoga Tab Plus, as the top of
 * the backlight scale of Android.
 *
 * The backlight of the panel has a normal range (the backlight value of the
 * composer, 0 to 1, up to {@link #mNormalMaxNits}) and a second range above it
 * that the stock firmware turns on in sunlight: the "hbm" node of the panel,
 * set through the Lenovo display HAL, scales the brightness of every backlight
 * value up by a fixed factor. The display configuration of the device puts both
 * on the backlight scale of Android: up to the transition point the normal
 * range, above it the nits between the normal maximum and the maximum with the
 * high brightness mode on (sunlight, and HDR video through HdrBrightnessModifier).
 *
 * The composer knows only the backlight value of the normal range, so the value
 * that Android means is sent in two parts: above the transition point the
 * "hbm" node is turned on, and the backlight value is lowered by the same
 * factor, so the brightness does not jump when the mode is entered, and goes up
 * to 1.0 again as the requested brightness goes up. The same happens in reverse
 * going down.
 *
 * Enabled by ro.vendor.display.hbm_bridge and a high brightness mode in the
 * display configuration.
 */
final class LenovoHbmBridge {
    private static final String TAG = "LenovoHbmBridge";

    private static final String DISPLAY_HAL = "vendor.lenovo.hardware.display.IDisplay";
    private static final int TRANSACTION_SET_HBM_STATE = 4;

    /** The step of the hbm node with the highest brightness (the panel has 1 and 2). */
    private static final int HBM_STATE_ON = 2;

    /** The backlight (composer scale) change that moves the mode, so a value on the border does not flip it. */
    private static final float HYSTERESIS = 0.002f;

    /** Time for the lowered backlight value to reach the panel before the mode turns on. */
    private static final long HBM_ON_DELAY_MS = 40;

    private final float mTransition;
    private final float mNormalMaxNits;
    private final float mHbmMaxNits;

    private final Handler mHandler;

    /** The mode that the backlight values sent last assume; only the display thread sets it. */
    private volatile boolean mHbmOn;
    private IBinder mHal;
    private final Object mHalLock = new Object();

    private LenovoHbmBridge(float transition, float normalMaxNits, float hbmMaxNits) {
        mTransition = transition;
        mNormalMaxNits = normalMaxNits;
        mHbmMaxNits = hbmMaxNits;
        HandlerThread thread = new HandlerThread("LenovoHbmBridge");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        // The panel keeps the mode over a restart of system_server: start from off
        mHandler.post(() -> writeHal(0));
    }

    /** The bridge for this display configuration, or null if the display has no such mode. */
    static LenovoHbmBridge create(DisplayDeviceConfig config) {
        if (!android.os.SystemProperties.getBoolean("ro.vendor.display.hbm_bridge", false)) {
            return null;
        }
        final HighBrightnessModeData hbm = config.getHighBrightnessModeData();
        if (hbm == null) {
            return null;
        }
        final float transition = config.getBacklightFromBrightness(hbm.transitionPoint);
        final float normalMaxNits = config.getNitsFromBacklight(transition);
        final float hbmMaxNits = config.getNitsFromBacklight(1.0f);
        if (!(transition > 0f && transition < 1f && normalMaxNits > 0f
                && hbmMaxNits > normalMaxNits)) {
            Slog.w(TAG, "unusable high brightness mode: transition " + transition
                    + ", nits " + normalMaxNits + " to " + hbmMaxNits);
            return null;
        }
        Slog.i(TAG, "backlight up to " + transition + " is " + normalMaxNits
                + " nits, high brightness mode up to " + hbmMaxNits + " nits");
        return new LenovoHbmBridge(transition, normalMaxNits, hbmMaxNits);
    }

    /**
     * The backlight value of the composer for the backlight value and nits that Android
     * means. Called for every brightness change; turns the mode of the panel on and off.
     *
     * @return the backlight value to send to the composer
     */
    float toComposer(float backlight, float nits, boolean isDisplayBacklight) {
        if (backlight < 0f) {
            // off
            if (isDisplayBacklight) {
                setHbm(false);
            }
            return backlight;
        }
        final boolean hbm;
        if (!isDisplayBacklight) {
            // the backlight of the SDR layers follows the mode that the display backlight sets
            hbm = mHbmOn;
        } else if (mHbmOn) {
            hbm = backlight > mTransition - HYSTERESIS;
        } else {
            hbm = backlight > mTransition + HYSTERESIS;
        }
        final float value;
        if (hbm && nits > 0f) {
            value = Math.min(1f, nits / mHbmMaxNits);
        } else {
            value = Math.min(1f, backlight / mTransition);
        }
        if (isDisplayBacklight) {
            setHbm(hbm);
        }
        return value;
    }

    private void setHbm(boolean on) {
        if (on == mHbmOn) {
            return;
        }
        mHbmOn = on;
        mHandler.removeCallbacksAndMessages(null);
        if (on) {
            // the lowered backlight value goes first, then the mode
            mHandler.postDelayed(() -> {
                if (mHbmOn) {
                    writeHal(HBM_STATE_ON);
                }
            }, HBM_ON_DELAY_MS);
        } else {
            // the mode goes first, then the backlight value rises (it is sent right after)
            writeHal(0);
        }
    }

    private void writeHal(int state) {
        synchronized (mHalLock) {
            if (mHal == null || !mHal.isBinderAlive()) {
                mHal = ServiceManager.checkService(DISPLAY_HAL + "/default");
            }
            if (mHal == null) {
                Slog.w(TAG, "no Lenovo display HAL");
                return;
            }
            final Parcel data = Parcel.obtain();
            final Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DISPLAY_HAL);
                data.writeInt(state);
                mHal.transact(TRANSACTION_SET_HBM_STATE, data, reply, 0);
                reply.readException();
                if (reply.readInt() == 0) {
                    Slog.w(TAG, "the panel did not take hbm state " + state);
                }
            } catch (Exception e) {
                Slog.w(TAG, "hbm state " + state + " failed", e);
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }
}
