/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.custom;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.KeyParameter;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.keybox.IKeyboxManager;
import android.security.keybox.KeyboxSignResult;
import android.system.keystore2.KeyDescriptor;
import android.util.AtomicFile;
import android.util.Log;

import com.android.internal.util.custom.IKeyboxProvider;
import com.android.internal.util.custom.KeyProviderManager;
import com.android.internal.util.custom.KeyboxChainGenerator;
import com.android.internal.util.custom.KeyboxChainGenerator.KeyGenParameters;
import com.android.internal.util.custom.KeyboxUtils;
import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Holds the keybox and signs attestation certificate chains inside system_server so the
 * keybox private keys never enter app processes.
 */
public final class KeyboxAttestationService extends SystemService {
    private static final String TAG = KeyboxAttestationService.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SERVICE_NAME = "keybox_attestation";
    private static final String CONFIG_FILE = "config.json";
    private static final String[] DEFAULT_TARGETS = {
        "com.google.android.gms", "com.android.vending"
    };
    private static final String PIF_CONFIG_SETTING = "lenovo_pif_module_config";
    private static final String PIF_DEFAULT_PROP = "/system_ext/etc/PlayIntegrityFix/pif.prop";
    private static final Pattern PATCH_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String TEE_SWITCH_SETTING = "lenovo_tee_simulator";

    private final Context mContext;
    private final Object mLock = new Object();
    private final File mDir;

    // All guarded by mLock
    private List<String> mTargetApps = new ArrayList<>(Arrays.asList(DEFAULT_TARGETS));
    private boolean mAutoTarget = true;
    private String mSecurityPatch = "";
    private boolean mRevoked = false;

    public KeyboxAttestationService(Context context) {
        super(context);
        mContext = context;
        mDir = new File(Environment.getDataSystemDirectory(), "keybox");
    }

    @Override
    public void onStart() {
        ensureDir();
        synchronized (mLock) {
            loadConfigLocked();
        }
        publishBinderService(SERVICE_NAME, mBinder);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            // The keybox is Settings.Secure keybox_data, as the developer-options preference
            // writes it; reload it whenever it changes.
            synchronized (mLock) {
                reloadKeyboxLocked();
            }
            Uri uri = Settings.Secure.getUriFor(Settings.Secure.KEYBOX_DATA);
            mContext.getContentResolver().registerContentObserver(uri, false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            synchronized (mLock) {
                                reloadKeyboxLocked();
                            }
                        }
                    }, UserHandle.USER_SYSTEM);
        }
    }

    private void ensureDir() {
        if (!mDir.exists() && !mDir.mkdirs()) {
            Log.e(TAG, "Failed to create " + mDir);
        }
        FileUtils.setPermissions(mDir.getAbsolutePath(), 0700, Process.myUid(), Process.myUid());
    }

    // ---- persistence ----

    private void loadConfigLocked() {
        File file = new File(mDir, CONFIG_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            JSONObject json = new JSONObject(
                    new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            JSONArray apps = json.optJSONArray("targetApps");
            if (apps != null) {
                List<String> list = new ArrayList<>();
                for (int i = 0; i < apps.length(); i++) {
                    list.add(apps.getString(i));
                }
                mTargetApps = list;
            }
            mAutoTarget = json.optBoolean("autoTarget", true);
            mSecurityPatch = json.optString("securityPatch", "");
            mRevoked = json.optBoolean("revoked", false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read config", e);
        }
    }

    private void saveConfigLocked() {
        AtomicFile file = new AtomicFile(new File(mDir, CONFIG_FILE));
        FileOutputStream out = null;
        try {
            JSONObject json = new JSONObject();
            json.put("targetApps", new JSONArray(mTargetApps));
            json.put("autoTarget", mAutoTarget);
            json.put("securityPatch", mSecurityPatch);
            json.put("revoked", mRevoked);
            out = file.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            FileUtils.setPermissions(new File(mDir, CONFIG_FILE).getAbsolutePath(), 0600,
                    Process.myUid(), Process.myUid());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write config", e);
            if (out != null) {
                file.failWrite(out);
            }
        }
    }

    /** Settings.Secure keybox_data wins; otherwise the built-in config_certifiedKeybox default. */
    private void reloadKeyboxLocked() {
        String xml = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.KEYBOX_DATA, UserHandle.USER_SYSTEM);
        if (xml != null && !xml.trim().isEmpty()) {
            try {
                KeyProviderManager.setProvider(KeyProviderManager.parseXml(xml));
                return;
            } catch (Exception e) {
                Log.e(TAG, "Stored keybox invalid", e);
            }
        }
        IKeyboxProvider builtIn = KeyProviderManager.fromConfigArray(mContext.getResources()
                .getStringArray(com.android.internal.R.array.config_certifiedKeybox));
        KeyProviderManager.setProvider(builtIn.hasKeybox() ? builtIn : null);
    }

    private void setKeyboxLocked(String xml) throws java.io.IOException {
        IKeyboxProvider provider = KeyProviderManager.parseXml(xml);
        try {
            // Fail early on unparsable PEM keys/certs
            KeyboxUtils.parsePrivateKey(provider.getEcPrivateKey(), "EC");
            KeyboxUtils.parsePrivateKey(provider.getRsaPrivateKey(), "RSA");
            for (String pem : provider.getEcCertificateChain()) KeyboxUtils.parseCertificate(pem);
            for (String pem : provider.getRsaCertificateChain()) KeyboxUtils.parseCertificate(pem);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid keybox key/certificate: " + e.getMessage());
        }
        if (!Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.KEYBOX_DATA, xml, UserHandle.USER_SYSTEM)) {
            throw new java.io.IOException("Failed to store keybox_data");
        }
        KeyProviderManager.setProvider(provider);
    }

    // ---- helpers ----

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) {
            return;
        }
        mContext.enforceCallingPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                "KeyboxAttestationService");
    }

    /** Settings.Global lenovo_tee_simulator: unset or 1 = on, only 0 turns the simulation off. */
    private boolean isTeeEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(), TEE_SWITCH_SETTING, 1) != 0;
    }

    private boolean isTargeted(int uid) {
        Set<String> targets = new LinkedHashSet<>();
        synchronized (mLock) {
            targets.addAll(mTargetApps);
            if (mAutoTarget) {
                targets.addAll(Arrays.asList(DEFAULT_TARGETS));
            }
        }
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (targets.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** Override, else the active PIF profile's SECURITY_PATCH, else "" (Build). */
    private String resolveSecurityPatch() {
        String override;
        synchronized (mLock) {
            override = mSecurityPatch;
        }
        if (!override.isEmpty()) {
            return override;
        }
        // Same pif.prop PIF applies: the user's config in Settings.Global, else the system default
        String text = null;
        try {
            text = Settings.Global.getString(mContext.getContentResolver(), PIF_CONFIG_SETTING);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read the PIF config", e);
        }
        if (text == null || text.isBlank()) {
            try {
                text = new String(Files.readAllBytes(new File(PIF_DEFAULT_PROP).toPath()),
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "No default pif.prop", e);
                text = "";
            }
        }
        // Same parsing as PifConfig.parseRaw: first occurrence wins, '#' comments
        for (String line : text.split("\n", -1)) {
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals("SECURITY_PATCH")) {
                String patch = line.substring(eq + 1).trim();
                if (PATCH_PATTERN.matcher(patch).matches()) {
                    return patch;
                }
                break;
            }
        }
        // Empty: KeyboxChainGenerator falls back to ro.build.version.security_patch
        return "";
    }

    private String buildStatus() {
        JSONObject json = new JSONObject();
        try {
            IKeyboxProvider provider = KeyProviderManager.getProvider();
            boolean present = provider.hasKeybox();
            JSONArray algorithms = new JSONArray();
            String notAfter = "";
            int ecCount = 0;
            int rsaCount = 0;
            if (present) {
                algorithms.put("EC");
                algorithms.put("RSA");
                String[] ec = provider.getEcCertificateChain();
                String[] rsa = provider.getRsaCertificateChain();
                ecCount = ec.length;
                rsaCount = rsa.length;
                Date earliest = null;
                for (String pem : new String[] {ec[0], rsa[0]}) {
                    Date d = KeyboxUtils.parseCertificate(pem).getNotAfter();
                    if (earliest == null || d.before(earliest)) earliest = d;
                }
                notAfter = earliest.toInstant().toString();
            }
            JSONArray serials = new JSONArray();
            if (present) {
                for (String pem : provider.getEcCertificateChain()) {
                    serials.put(KeyboxUtils.parseCertificate(pem)
                            .getSerialNumber().toString(16));
                }
                for (String pem : provider.getRsaCertificateChain()) {
                    serials.put(KeyboxUtils.parseCertificate(pem)
                            .getSerialNumber().toString(16));
                }
            }
            json.put("present", present);
            json.put("algorithms", algorithms);
            json.put("ecCertCount", ecCount);
            json.put("rsaCertCount", rsaCount);
            json.put("notAfter", notAfter);
            json.put("serials", serials);
            synchronized (mLock) {
                json.put("revoked", mRevoked);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build status", e);
        }
        return json.toString();
    }

    private KeyboxSignResult sign(int uid, String alias, byte[] keyParams) {
        if (!isTeeEnabled() || !KeyProviderManager.getProvider().hasKeybox()) {
            return null;
        }
        synchronized (mLock) {
            if (mRevoked) {
                return null;
            }
        }
        long token = Binder.clearCallingIdentity();
        try {
            if (!isTargeted(uid)) {
                return null;
            }
            KeyParameter[] params;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(keyParams, 0, keyParams.length);
                parcel.setDataPosition(0);
                params = parcel.createTypedArray(KeyParameter.CREATOR);
            } finally {
                parcel.recycle();
            }
            KeyGenParameters gen = new KeyGenParameters(params);
            if (gen.attestationChallenge == null
                    || (gen.algorithm != Algorithm.EC && gen.algorithm != Algorithm.RSA)) {
                return null;
            }
            KeyDescriptor descriptor = new KeyDescriptor();
            descriptor.alias = alias;

            KeyboxChainGenerator.setSecurityPatch(resolveSecurityPatch());
            List<Certificate> chain = KeyboxChainGenerator.generateCertChain(uid, descriptor, gen);
            if (chain == null || chain.isEmpty()) {
                return null;
            }
            KeyboxSignResult result = new KeyboxSignResult();
            result.certificate = chain.get(0).getEncoded();
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            for (int i = 1; i < chain.size(); i++) {
                rest.write(chain.get(i).getEncoded());
            }
            result.certificateChain = rest.toByteArray();
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sign attested key", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private final IKeyboxManager.Stub mBinder = new IKeyboxManager.Stub() {
        @Override
        public boolean hasKeybox() {
            enforceCaller();
            return KeyProviderManager.getProvider().hasKeybox();
        }

        @Override
        public String getKeyboxStatus() {
            enforceCaller();
            return buildStatus();
        }

        @Override
        public void setKeybox(String xml) {
            enforceCaller();
            if (xml == null) {
                throw new IllegalArgumentException("xml is null");
            }
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    setKeyboxLocked(xml);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to persist keybox", e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void clearKeybox() {
            enforceCaller();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            Settings.Secure.KEYBOX_DATA, null, UserHandle.USER_SYSTEM);
                    reloadKeyboxLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<String> getTargetApps() {
            enforceCaller();
            synchronized (mLock) {
                return new ArrayList<>(mTargetApps);
            }
        }

        @Override
        public void setTargetApps(List<String> packageNames) {
            enforceCaller();
            synchronized (mLock) {
                mTargetApps = new ArrayList<>(new LinkedHashSet<>(packageNames));
                saveConfigLocked();
            }
        }

        @Override
        public boolean isAutoTarget() {
            enforceCaller();
            synchronized (mLock) {
                return mAutoTarget;
            }
        }

        @Override
        public void setAutoTarget(boolean enabled) {
            enforceCaller();
            synchronized (mLock) {
                mAutoTarget = enabled;
                saveConfigLocked();
            }
        }

        @Override
        public String getSecurityPatch() {
            enforceCaller();
            synchronized (mLock) {
                return mSecurityPatch;
            }
        }

        @Override
        public void setSecurityPatch(String patch) {
            enforceCaller();
            if (patch == null) patch = "";
            if (!patch.isEmpty() && !PATCH_PATTERN.matcher(patch).matches()) {
                throw new IllegalArgumentException("Security patch must be YYYY-MM-DD or empty");
            }
            synchronized (mLock) {
                mSecurityPatch = patch;
                saveConfigLocked();
            }
        }

        @Override
        public void setRevoked(boolean revoked) {
            enforceCaller();
            synchronized (mLock) {
                mRevoked = revoked;
                saveConfigLocked();
            }
        }

        @Override
        public boolean isKeyboxActive() {
            return isTeeEnabled() && KeyProviderManager.getProvider().hasKeybox();
        }

        @Override
        public KeyboxSignResult signAttestedKey(String alias, byte[] keyParams) {
            return sign(Binder.getCallingUid(), alias, keyParams);
        }
    };
}
