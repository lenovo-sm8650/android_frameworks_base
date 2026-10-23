/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keybox holder. The keybox is only ever loaded inside system_server
 * (KeyboxAttestationService); in any other process the provider is empty and
 * availability is answered by the service.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";

    private static volatile IKeyboxProvider sProvider;

    private KeyProviderManager() {}

    public static IKeyboxProvider getProvider() {
        IKeyboxProvider provider = sProvider;
        return provider != null ? provider : new DefaultKeyboxProvider(new HashMap<>());
    }

    public static boolean isKeyboxAvailable() {
        IKeyboxProvider provider = sProvider;
        if (provider != null) {
            return provider.hasKeybox();
        }
        return KeyboxImitationHooks.isKeyboxActive();
    }

    /**
     * Parses and validates keybox XML without loading it.
     * @throws IllegalArgumentException if the XML is not a complete keybox
     */
    public static IKeyboxProvider parseXml(String xml) {
        DefaultKeyboxProvider provider = new DefaultKeyboxProvider(new HashMap<>());
        if (!provider.loadXml(xml) || !provider.hasKeybox()) {
            throw new IllegalArgumentException("Invalid keybox XML");
        }
        return provider;
    }

    /** system_server only: builds a provider from "KEY:value" config entries. */
    public static IKeyboxProvider fromConfigArray(String[] entries) {
        Map<String, String> data = new HashMap<>();
        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                data.put(parts[0], parts[1]);
            }
        }
        return new DefaultKeyboxProvider(data);
    }

    /** system_server only: installs the active keybox (null clears it). */
    public static void setProvider(IKeyboxProvider provider) {
        sProvider = provider;
    }

    private static class DefaultKeyboxProvider implements IKeyboxProvider {
        private final Map<String, String> keyboxData;

        private DefaultKeyboxProvider(Map<String, String> data) {
            keyboxData = data;
        }

        private boolean loadXml(String xml) {
            try {
                if (xml == null || xml.trim().isEmpty()) return false;

                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(xml));

                String currentAlg = null;
                int certCount = 0;
                boolean numberOfKeyboxesChecked = false;

                for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        switch (tag) {
                            case "NumberOfKeyboxes":
                                p.next();
                                numberOfKeyboxesChecked = true;
                                try {
                                    int count = Integer.parseInt(p.getText().trim());
                                    if (count != 1) {
                                        Log.w(TAG, "Invalid NumberOfKeyboxes: " + count);
                                        return false;
                                    }
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "Failed to parse NumberOfKeyboxes", e);
                                    return false;
                                }
                                break;

                            case "Key":
                                currentAlg = p.getAttributeValue(null, "algorithm");
                                if ("ecdsa".equalsIgnoreCase(currentAlg)) currentAlg = "EC";
                                else if ("rsa".equalsIgnoreCase(currentAlg)) currentAlg = "RSA";
                                else currentAlg = null;
                                certCount = 0;
                                break;

                            case "PrivateKey": {
                                String format = p.getAttributeValue(null, "format");
                                if (!"pem".equalsIgnoreCase(format)) {
                                    Log.w(TAG, "Unsupported PrivateKey format: " + format);
                                    return false;
                                }
                                p.next();
                                if (currentAlg != null) {
                                    keyboxData.put(currentAlg + ".PRIV", p.getText().trim());
                                }
                                break;
                            }

                            case "Certificate": {
                                String format = p.getAttributeValue(null, "format");
                                if (!"pem".equalsIgnoreCase(format)) {
                                    Log.w(TAG, "Unsupported Certificate format: " + format);
                                    return false;
                                }
                                if (currentAlg != null) {
                                    p.next();
                                    certCount++;
                                    keyboxData.put(currentAlg + ".CERT_" + certCount, p.getText().trim());
                                }
                                break;
                            }
                        }
                    }
                }

                if (!numberOfKeyboxesChecked) {
                    Log.w(TAG, "Missing <NumberOfKeyboxes> in keybox XML");
                    return false;
                }

                return hasKeybox();
            } catch (Exception e) {
                Log.e(TAG, "XML keybox load failed", e);
                return false;
            }
        }

        @Override
        public boolean hasKeybox() {
            if (!keyboxData.containsKey("EC.PRIV") || !keyboxData.containsKey("RSA.PRIV")) {
                return false;
            }
            if (!keyboxData.containsKey("EC.CERT_1") || !keyboxData.containsKey("RSA.CERT_1")) {
                return false;
            }
            return true;
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            List<String> chain = new ArrayList<>();
            for (int i = 1; keyboxData.containsKey(prefix + ".CERT_" + i); i++) {
                chain.add(keyboxData.get(prefix + ".CERT_" + i));
            }
            return chain.toArray(String[]::new);
        }
    }
}
