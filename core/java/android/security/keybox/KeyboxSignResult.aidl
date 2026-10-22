/*
 * SPDX-License-Identifier: Apache-2.0
 */

package android.security.keybox;

/**
 * Signed attestation certificates, laid out like KeyMetadata.certificate/certificateChain.
 * @hide
 */
parcelable KeyboxSignResult {
    /** DER leaf certificate. */
    byte[] certificate;
    /** Concatenated DER of the remaining chain. */
    byte[] certificateChain;
}
