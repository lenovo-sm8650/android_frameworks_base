/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display.mode;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.test.TestLooper;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@SmallTest
public class StylusObserverTest {
    private static final int TIMEOUT_MS = 10_000;

    private final Context mContext = mock(Context.class);
    private final Resources mResources = mock(Resources.class);
    private final DisplayManager mDisplayManager = mock(DisplayManager.class);
    private final Display mDisplay = mock(Display.class);
    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());
    private final VotesStorage mStorage = new VotesStorage(() -> { }, null);
    private Display.Mode[] mModes = {
            mode(30), mode(60), mode(90), mode(120), mode(144)
    };
    private StylusObserver mObserver;
    private DisplayManager.DisplayListener mDisplayListener;

    @Before
    public void setUp() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);
        when(mResources.getIntArray(R.array.config_stylusIncompatibleRefreshRates))
                .thenReturn(new int[] {90, 144});
        when(mResources.getInteger(R.integer.config_stylusRefreshRateTimeoutMs))
                .thenReturn(TIMEOUT_MS);
        when(mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(mDisplay);
        when(mDisplay.getState()).thenReturn(Display.STATE_ON);
        when(mDisplay.getMode()).thenReturn(mode(144));
        mObserver = new StylusObserver(mContext, mStorage, mHandler, id -> mModes);
        mObserver.observe();
        ArgumentCaptor<DisplayManager.DisplayListener> listener =
                ArgumentCaptor.forClass(DisplayManager.DisplayListener.class);
        verify(mDisplayManager).registerDisplayListener(listener.capture(), any(Handler.class));
        mDisplayListener = listener.getValue();
    }

    @Test
    public void detectionAt144PinsBothRangesTo120() {
        detect();
        assertPinnedTo120();
    }

    @Test
    public void detectionAt90PinsBothRangesTo120RatherThan60() {
        when(mDisplay.getMode()).thenReturn(mode(90));
        detect();
        assertPinnedTo120();
    }

    @Test
    public void compatibleModesAreLeftAlone() {
        for (int rate : new int[] {30, 60, 120}) {
            when(mDisplay.getMode()).thenReturn(mode(rate));
            detect();
            assertThat(stylusVote()).isNull();
        }
    }

    @Test
    public void transitionTo120KeepsVoteUntilTimeout() {
        detect();
        when(mDisplay.getMode()).thenReturn(mode(120));
        mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        assertPinnedTo120();
        advance(TIMEOUT_MS - 1);
        assertPinnedTo120();
        advance(1);
        assertThat(stylusVote()).isNull();
    }

    @Test
    public void penMotionAt120RenewsTimeout() {
        detect();
        advance(9_000);
        when(mDisplay.getMode()).thenReturn(mode(120));
        detect();
        // The original timeout must not remove the renewed vote.
        advance(1_000);
        assertPinnedTo120();
        advance(8_999);
        assertPinnedTo120();
        advance(1);
        assertThat(stylusVote()).isNull();
    }

    @Test
    public void switchToIncompatibleModeWhilePenNearPinsTo120() {
        when(mDisplay.getMode()).thenReturn(mode(60));
        detect();
        when(mDisplay.getMode()).thenReturn(mode(90));
        mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        assertPinnedTo120();
    }

    @Test
    public void screenOffReleasesVote() {
        detect();
        when(mDisplay.getState()).thenReturn(Display.STATE_OFF);
        mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        assertThat(stylusVote()).isNull();
        when(mDisplay.getState()).thenReturn(Display.STATE_ON);
        mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        assertThat(stylusVote()).isNull();
    }

    @Test
    public void removedDisplayReleasesVote() {
        detect();
        mDisplayListener.onDisplayRemoved(Display.DEFAULT_DISPLAY);
        assertThat(stylusVote()).isNull();
    }

    @Test
    public void missing120ModeDoesNotCreateImpossibleVote() {
        mModes = new Display.Mode[] {mode(60), mode(90), mode(144)};
        detect();
        assertThat(stylusVote()).isNull();
    }

    @Test
    public void targetMustMatchCurrentResolution() {
        mModes = new Display.Mode[] {new Display.Mode(1, 1920, 1080, 120)};
        detect();
        assertThat(stylusVote()).isNull();
    }

    @Test
    public void constructorDoesNotObtainDisplayServiceBeforeItIsPublished() {
        Context earlyContext = mock(Context.class);
        when(earlyContext.getResources()).thenReturn(mResources);
        new StylusObserver(earlyContext, mStorage, mHandler, id -> mModes);
        verify(earlyContext, never()).getSystemService(DisplayManager.class);
    }

    @Test
    public void disabledDeviceDoesNotObserveOrVote() {
        when(mResources.getIntArray(R.array.config_stylusIncompatibleRefreshRates))
                .thenReturn(new int[0]);
        DisplayManager manager = mock(DisplayManager.class);
        when(mContext.getSystemService(DisplayManager.class)).thenReturn(manager);
        StylusObserver disabled = new StylusObserver(mContext, mStorage, mHandler, id -> mModes);
        disabled.observe();
        disabled.onStylusDetected();
        mLooper.dispatchAll();
        verify(manager, never()).registerDisplayListener(any(), any(Handler.class));
        assertThat(stylusVote()).isNull();
    }

    private void detect() {
        mObserver.onStylusDetected();
        mLooper.dispatchAll();
    }

    private void advance(long millis) {
        mLooper.moveTimeForward(millis);
        mLooper.dispatchAll();
    }

    private Vote stylusVote() {
        return mStorage.getVotes(Display.DEFAULT_DISPLAY).get(Vote.PRIORITY_STYLUS);
    }

    private void assertPinnedTo120() {
        Vote vote = stylusVote();
        assertThat(vote).isNotNull();
        VoteSummary summary = new VoteSummary(false, false, true, false);
        vote.updateSummary(summary);
        assertThat(summary.minPhysicalRefreshRate).isEqualTo(120f);
        assertThat(summary.maxPhysicalRefreshRate).isEqualTo(120f);
        assertThat(summary.minRenderFrameRate).isEqualTo(120f);
        assertThat(summary.maxRenderFrameRate).isEqualTo(120f);
    }

    private static Display.Mode mode(int rate) {
        return new Display.Mode(rate, 2944, 1840, rate);
    }
}
