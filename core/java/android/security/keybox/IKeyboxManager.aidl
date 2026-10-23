/*
 * SPDX-License-Identifier: Apache-2.0
 */

package android.security.keybox;

import android.security.keybox.KeyboxSignResult;

/**
 * Binder interface of KeyboxAttestationService ("keybox_attestation") in system_server.
 * The keybox private keys never leave system_server.
 *
 * Every method except isKeyboxActive() and signAttestedKey() requires the caller to hold
 * android.permission.WRITE_SECURE_SETTINGS or be the system uid.
 * @hide
 */
interface IKeyboxManager {
    boolean hasKeybox();
    /** JSON: {"present","algorithms","ecCertCount","rsaCertCount","notAfter","revoked"} */
    String getKeyboxStatus();
    /** Validates and persists the keybox XML; throws IllegalArgumentException if invalid. */
    void setKeybox(String xml);
    void clearKeybox();
    List<String> getTargetApps();
    void setTargetApps(in List<String> packageNames);
    boolean isAutoTarget();
    void setAutoTarget(boolean enabled);
    /** "" means auto (active PIF profile / build). */
    String getSecurityPatch();
    /** "" or YYYY-MM-DD. */
    void setSecurityPatch(String patch);
    void setRevoked(boolean revoked);

    /** Framework-internal, ungated: whether a usable keybox is loaded. */
    boolean isKeyboxActive();
    /**
     * Framework-internal signing path used by the keystore hook. The caller uid is taken from
     * Binder; returns null unless it belongs to a targeted package.
     * keyParams is a Parcel-marshalled KeyParameter[] (typed array).
     */
    @nullable KeyboxSignResult signAttestedKey(String alias, in byte[] keyParams);
}
