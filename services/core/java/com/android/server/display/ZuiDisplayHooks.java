/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.server.display;

import android.content.res.Resources;

import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * The brightness logic of the stock Lenovo ZUI framework (services.jar), which is on when
 * {@code config_zuiAutoBrightness} is true (the "ZuiAutoBrightness" feature of the stock
 * firmware), and the hooks that stock ZuiDisplayServiceInternal gives to the high brightness
 * mode of the panel.
 *
 * The hooks are plain java.util.function fields, set by reflection from the Lenovo input
 * bridge (hardware/lenovo/input), which runs in system_server but cannot link to services.jar.
 */
public final class ZuiDisplayHooks {
    /**
     * Ratio of the nits of the backlight to the nits of the brightness while Dolby Vision plays,
     * stock DisplayPowerController.updatePowerStateInternal (getHdrBrightnessFromSdr(brightness,
     * 2.0f) with mDolbyVisionStatus or mCameraDolbyVisionStatus).
     */
    static final float BACKLIGHT_NITS_RATIO = 2.0f;

    /** Stock AutomaticBrightnessController.updateAutoBrightness: minimum brightness step. */
    static final float AUTO_BRIGHTNESS_STEP = 0.05f;

    /** Stock DisplayPowerController.mScreenBrightnessDeviation. */
    static final float SCREEN_BRIGHTNESS_DEVIATION = 0.025f;

    /** Stock DisplayPowerController.useLenovoBrightnessRampRate: seconds of a full sweep. */
    static final float RAMP_SECONDS_INCREASING = 5.0f;
    static final float RAMP_SECONDS_DECREASING = 33.0f;

    /** Stock ZuiDisplayServiceInternal.setLux: filtered ambient lux of the automatic brightness. */
    public static volatile DoubleConsumer sLux;
    /** Stock ZuiDisplayServiceInternal.setBrightness: the brightness the display power gets. */
    public static volatile DoubleConsumer sBrightness;
    /** Stock ZuiDisplayServiceInternal.setAutoBrightnessEnable: 1 when automatic, 0 otherwise. */
    public static volatile IntConsumer sAutoBrightness;

    private static Boolean sZuiAutoBrightness;

    private ZuiDisplayHooks() {}

    /** The stock "ZuiAutoBrightness" feature. */
    static synchronized boolean isAutoBrightness() {
        if (sZuiAutoBrightness == null) {
            sZuiAutoBrightness = Resources.getSystem().getBoolean(
                    com.android.internal.R.bool.config_zuiAutoBrightness);
        }
        return sZuiAutoBrightness;
    }

    static void setLux(float lux) {
        final DoubleConsumer c = sLux;
        if (c != null) {
            c.accept(lux);
        }
    }

    static void setBrightness(float brightness) {
        final DoubleConsumer c = sBrightness;
        if (c != null) {
            c.accept(brightness);
        }
    }

    static void setAutoBrightnessEnable(boolean enable) {
        final IntConsumer c = sAutoBrightness;
        if (c != null) {
            c.accept(enable ? 1 : 0);
        }
    }
}
