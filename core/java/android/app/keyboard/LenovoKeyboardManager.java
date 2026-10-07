/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.app.keyboard;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Client of the "lenovokeyboard" service, which the Lenovo keyboard firmware
 * updater (ZuiKeyboardUpdate) looks up with getSystemService() and calls by
 * reflection. The service is published by the device's DeviceKeyHandler and
 * forwards to the vendor keyboard HAL.
 *
 * @hide
 */
public class LenovoKeyboardManager {
    private static final String TAG = "LenovoKeyboardManager";

    /** Name of the service, as the stock Context.LENOVOKEYBOARD_SERVICE. */
    public static final String SERVICE = "lenovokeyboard";

    private final ILenovoKeyboardService mService;

    public LenovoKeyboardManager(Context context) {
        IBinder b = ServiceManager.getService(SERVICE);
        mService = b != null ? ILenovoKeyboardService.Stub.asInterface(b) : null;
        if (mService == null) Log.w(TAG, SERVICE + " service not found");
    }

    public int kb_open(String path) {
        if (mService == null) return 0;
        try {
            return mService.kb_open(path);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_open", e);
            return 0;
        }
    }

    public String kb_read(int fd, int size) {
        if (mService == null) return null;
        try {
            return mService.kb_read(fd, size);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_read", e);
            return null;
        }
    }

    public byte[] kb_read_data(int fd, int size) {
        if (mService == null) return null;
        try {
            return mService.kb_read_data(fd, size);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_read_data", e);
            return null;
        }
    }

    public int kb_write_data(int fd, int size, byte[] data) {
        if (mService == null) return 0;
        try {
            return mService.kb_write_data(fd, size, data);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_write_data", e);
            return 0;
        }
    }

    public void kb_close(int fd) {
        if (mService == null) return;
        try {
            mService.kb_close(fd);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_close", e);
        }
    }

    public byte[] kb_getfeature(int fd, int size, byte[] data) {
        if (mService == null) return null;
        try {
            return mService.kb_getfeature(fd, size, data);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_getfeature", e);
            return null;
        }
    }

    public int kb_setfeature(int fd, byte[] data, int size) {
        if (mService == null) return 0;
        try {
            return mService.kb_setfeature(fd, data, size);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_setfeature", e);
            return 0;
        }
    }

    public String kb_getrawname(int fd) {
        if (mService == null) return null;
        try {
            return mService.kb_getrawname(fd);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_getrawname", e);
            return null;
        }
    }

    public byte[] kb_getrawinfo(int fd) {
        if (mService == null) return null;
        try {
            return mService.kb_getrawinfo(fd);
        } catch (RemoteException e) {
            Log.e(TAG, "kb_getrawinfo", e);
            return null;
        }
    }
}
