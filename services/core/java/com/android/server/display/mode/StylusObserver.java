/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display.mode;

import android.content.Context;
import android.os.Handler;
import android.util.ArraySet;
import android.view.Display;

import com.android.internal.R;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Keeps the default display off the refresh rates at which its touchscreen cannot track a
 * stylus, while a stylus is near the screen.
 *
 * Some panels turn the stylus scanning of their touch controller off at some refresh rates. The
 * touch controller still reports that it detects a stylus nearby, with the key of
 * config_stylusDetectScanCode, so the display leaves those refresh rates when that key comes and
 * may return to them config_stylusRefreshRateTimeoutMs after the last one.
 */
final class StylusObserver {
    private final VotesStorage mVotesStorage;
    private final Handler mHandler;
    private final IntFunction<Display.Mode[]> mModesProvider;
    private final int[] mIncompatibleRefreshRates;
    private final long mTimeoutMs;
    private final Runnable mReleaseRunnable = this::release;

    // Accessed on mHandler only.
    private boolean mStylusNear;

    StylusObserver(Context context, VotesStorage votesStorage, Handler handler,
            IntFunction<Display.Mode[]> modesProvider) {
        mVotesStorage = votesStorage;
        mHandler = handler;
        mModesProvider = modesProvider;
        mIncompatibleRefreshRates = context.getResources().getIntArray(
                R.array.config_stylusIncompatibleRefreshRates);
        mTimeoutMs = context.getResources().getInteger(
                R.integer.config_stylusRefreshRateTimeoutMs);
    }

    /** Called for every stylus detection of the touchscreen, from the input thread. */
    void onStylusDetected() {
        if (mIncompatibleRefreshRates.length == 0) {
            return;
        }
        mHandler.post(() -> {
            if (!mStylusNear) {
                mStylusNear = true;
                updateVote();
            }
            mHandler.removeCallbacks(mReleaseRunnable);
            mHandler.postDelayed(mReleaseRunnable, mTimeoutMs);
        });
    }

    private void release() {
        mStylusNear = false;
        mVotesStorage.updateVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_STYLUS, null);
    }

    private void updateVote() {
        final Display.Mode[] modes = mModesProvider.apply(Display.DEFAULT_DISPLAY);
        final Set<Integer> rejected = new ArraySet<>();
        if (modes != null) {
            for (Display.Mode mode : modes) {
                final int rate = Math.round(mode.getRefreshRate());
                for (int incompatible : mIncompatibleRefreshRates) {
                    if (rate == incompatible) {
                        rejected.add(mode.getSfModeId());
                        break;
                    }
                }
            }
        }
        mVotesStorage.updateVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_STYLUS,
                rejected.isEmpty() ? null : Vote.forRejectedModes(rejected));
    }

    void dump(PrintWriter pw) {
        pw.println("  StylusObserver");
        pw.println("    mIncompatibleRefreshRates: " + Arrays.toString(mIncompatibleRefreshRates));
        pw.println("    mTimeoutMs: " + mTimeoutMs);
        pw.println("    mStylusNear: " + mStylusNear);
    }
}
