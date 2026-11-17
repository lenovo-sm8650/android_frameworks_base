/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.custom;

import android.content.Context;
import android.content.res.Resources;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

/**
 * Shows another device identity (brand, manufacturer, model) to selected apps,
 * e.g. a device a streaming app lists for its higher playback quality.
 *
 * The apps come from Settings.Global device_spoof_apps =
 * "pkg=brand|manufacturer|model;...". While the key is unset, config_deviceSpoofApps
 * applies (same entries; a bare package name uses config_deviceSpoof{Brand,
 * Manufacturer,Model}). Empty fields keep the real value, and the defaults are empty,
 * so a build without an overlay changes nothing. Settings.Global device_spoof = 0
 * turns the feature off.
 *
 * Runs after PixelPropsUtils, so the identity of a selected app wins. Play services
 * and the Play Store are never changed.
 */
public final class DeviceSpoofUtils {
    private static final String TAG = "DeviceSpoofUtils";

    private static final String DEVICE_SPOOF = "device_spoof";
    private static final String DEVICE_SPOOF_APPS = "device_spoof_apps";

    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PACKAGE_FINSKY = "com.android.vending";

    private static final String[] PROPS = {"BRAND", "MANUFACTURER", "MODEL"};

    private DeviceSpoofUtils() {}

    public static void setProps(Context context) {
        if (context == null || Process.isIsolated()) {
            return;
        }
        final String packageName = context.getPackageName();
        if (TextUtils.isEmpty(packageName) || PACKAGE_GMS.equals(packageName)
                || PACKAGE_FINSKY.equals(packageName)) {
            return;
        }
        try {
            if (Settings.Global.getInt(context.getContentResolver(), DEVICE_SPOOF, 1) == 0) {
                return;
            }
            final Resources res = context.getResources();
            final String apps = Settings.Global.getString(context.getContentResolver(),
                    DEVICE_SPOOF_APPS);
            final String[] entries = apps != null ? apps.split(";")
                    : res.getStringArray(R.array.config_deviceSpoofApps);
            for (String entry : entries) {
                final int eq = entry.indexOf('=');
                final String pkg = eq >= 0 ? entry.substring(0, eq) : entry;
                if (!pkg.equals(packageName)) {
                    continue;
                }
                final String[] fields = eq >= 0 ? entry.substring(eq + 1).split("\\|", -1)
                        : new String[] {
                            res.getString(R.string.config_deviceSpoofBrand),
                            res.getString(R.string.config_deviceSpoofManufacturer),
                            res.getString(R.string.config_deviceSpoofModel),
                        };
                for (int i = 0; i < PROPS.length && i < fields.length; i++) {
                    if (!TextUtils.isEmpty(fields[i])) {
                        PixelPropsUtils.setPropValue(PROPS[i], fields[i]);
                    }
                }
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply the device identity for " + packageName, e);
        }
    }
}
