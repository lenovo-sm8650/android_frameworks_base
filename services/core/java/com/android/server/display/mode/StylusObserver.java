/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display.mode;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;

import com.android.internal.R;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Keeps the default display off the refresh rates at which its touchscreen cannot track a
 * stylus, while a stylus is near the screen.
 *
 * Some panels turn the stylus scanning of their touch controller off at some refresh rates. The
 * touch controller still reports that it detects a stylus nearby, with the key of
 * config_stylusDetectScanCode. At an incompatible rate, pin both the physical and rendering
 * ranges to 120Hz, so SurfaceFlinger cannot select an incompatible mode again. Detection keys
 * and actual stylus motion keep the vote alive until config_stylusRefreshRateTimeoutMs after
 * the last event. Displays already running at a compatible rate are left alone.
 */
final class StylusObserver {
    private static final int STYLUS_REFRESH_RATE = 120;

    private final Context mContext;
    private final VotesStorage mVotesStorage;
    private final Handler mHandler;
    private final IntFunction<Display.Mode[]> mModesProvider;
    private final int[] mIncompatibleRefreshRates;
    private final long mTimeoutMs;
    private final Runnable mReleaseRunnable = this::release;
    private DisplayManager mDisplayManager;

    // Accessed on mHandler only.
    private boolean mStylusNear;
    private float mPinnedRefreshRate;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) { }

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        release();
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY || !mStylusNear) {
                        return;
                    }
                    final Display display = mDisplayManager.getDisplay(displayId);
                    if (display == null || display.getState() != Display.STATE_ON) {
                        release();
                    } else {
                        updateVote();
                    }
                }
            };

    StylusObserver(Context context, VotesStorage votesStorage, Handler handler,
            IntFunction<Display.Mode[]> modesProvider) {
        mContext = context;
        mVotesStorage = votesStorage;
        mHandler = handler;
        mModesProvider = modesProvider;
        mIncompatibleRefreshRates = context.getResources().getIntArray(
                R.array.config_stylusIncompatibleRefreshRates);
        mTimeoutMs = context.getResources().getInteger(
                R.integer.config_stylusRefreshRateTimeoutMs);
    }

    void observe() {
        if (mIncompatibleRefreshRates.length == 0) {
            return;
        }
        // The observer is constructed before DisplayManagerService publishes its binder.
        // Obtain DisplayManager only after that service is ready.
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        }
    }

    /** Called for touchscreen detection keys and stylus motion, from the input thread. */
    void onStylusDetected() {
        if (mIncompatibleRefreshRates.length == 0) {
            return;
        }
        mHandler.post(() -> {
            mStylusNear = true;
            updateVote();
            mHandler.removeCallbacks(mReleaseRunnable);
            mHandler.postDelayed(mReleaseRunnable, mTimeoutMs);
        });
    }

    private void release() {
        mHandler.removeCallbacks(mReleaseRunnable);
        mStylusNear = false;
        mPinnedRefreshRate = 0;
        mVotesStorage.updateVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_STYLUS, null);
    }

    private void updateVote() {
        // Keep the vote after the transition to 120Hz; otherwise it would immediately undo
        // itself and oscillate between 120Hz and the user's original setting.
        if (mPinnedRefreshRate != 0 || mDisplayManager == null) {
            return;
        }
        final Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getState() != Display.STATE_ON) {
            return;
        }
        final Display.Mode currentMode = display.getMode();
        if (Arrays.stream(mIncompatibleRefreshRates)
                .noneMatch(rate -> rate == Math.round(currentMode.getRefreshRate()))) {
            return;
        }
        final Display.Mode[] modes = mModesProvider.apply(Display.DEFAULT_DISPLAY);
        if (modes != null) {
            for (Display.Mode mode : modes) {
                if (Math.round(mode.getRefreshRate()) == STYLUS_REFRESH_RATE
                        && mode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                        && mode.getPhysicalHeight() == currentMode.getPhysicalHeight()) {
                    mPinnedRefreshRate = mode.getRefreshRate();
                    mVotesStorage.updateVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_STYLUS,
                            new CombinedVote(List.of(
                                    Vote.forPhysicalRefreshRates(mPinnedRefreshRate,
                                            mPinnedRefreshRate),
                                    Vote.forRenderFrameRates(mPinnedRefreshRate))));
                    return;
                }
            }
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  StylusObserver");
        pw.println("    mIncompatibleRefreshRates: " + Arrays.toString(mIncompatibleRefreshRates));
        pw.println("    mTimeoutMs: " + mTimeoutMs);
        pw.println("    mStylusNear: " + mStylusNear);
        pw.println("    mPinnedRefreshRate: " + mPinnedRefreshRate);
    }
}
