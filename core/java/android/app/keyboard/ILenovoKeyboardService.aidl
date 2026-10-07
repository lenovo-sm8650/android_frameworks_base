/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.app.keyboard;

/**
 * Raw access to the Lenovo keyboard (pogo pin serial port / hidraw) through the
 * vendor keyboard HAL, for the Lenovo keyboard firmware updater. Same
 * interface and transaction order as the stock ZUI service.
 *
 * @hide
 */
interface ILenovoKeyboardService {
    int kb_open(String path);
    String kb_read(int fd, int size);
    byte[] kb_read_data(int fd, int size);
    int kb_write_data(int fd, int size, inout byte[] data);
    void kb_close(int fd);
    byte[] kb_getfeature(int fd, int size, inout byte[] data);
    int kb_setfeature(int fd, inout byte[] data, int size);
    String kb_getrawname(int fd);
    byte[] kb_getrawinfo(int fd);
}
