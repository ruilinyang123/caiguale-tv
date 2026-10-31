package com.fongmi.android.tv.remote;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Device;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device identity is kept outside backup/sync and encrypted with an app Keystore key. */
public final class RemoteStore {
    private static final String KEY_ALIAS = "webtv.public.remote.v1";
    private static final String FILE_NAME = "remote-v1.bin";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static RemoteModels.Profile cache;
    private static boolean unreadableIdentity;

    private RemoteStore() {}

    public static synchronized RemoteModels.Profile get() {
        if (cache != null) return cache;
        try {
            byte[] encrypted = read(file());
            if (encrypted.length > 12) {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encrypted, 0, 12));
                byte[] clear = cipher.doFinal(encrypted, 12, encrypted.length - 12);
                cache = App.gson().fromJson(new String(clear, StandardCharsets.UTF_8), RemoteModels.Profile.class);
            }
        } catch (Throwable ignored) {
            unreadableIdentity = file().isFile();
        }
        if (cache == null) cache = new RemoteModels.Profile();
        if (cache.groupIds == null) cache.groupIds = new ArrayList<>();
        if (cache.groups == null) cache.groups = new ArrayList<>();
        return cache;
    }

    /** Snapshot prevents a socket/client from observing mutable UI/store state. */
    public static synchronized RemoteModels.Profile snapshot() { return get().copy(); }

    public static synchronized void save() {
        try {
            byte[] clear = App.gson().toJson(get()).getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(clear);
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            File target = file();
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                stream.write(output);
                stream.flush();
                stream.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable atomicUnsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable error) {
            Log.w("RemoteStore", "Unable to persist encrypted remote identity", error);
        }
    }

    /** Changing relay origin deliberately discards the old identity; it is never reused cross-server. */
    public static synchronized boolean configure(String serverUrl, boolean enabled) {
        RemoteModels.Profile p = get();
        String normalized = normalize(serverUrl);
        if (!isHttpsOrigin(normalized)) throw new IllegalArgumentException("HTTPS relay required");
        boolean changed = !TextUtils.isEmpty(p.serverUrl) && !TextUtils.equals(p.serverUrl, normalized);
        if (changed) {
            p.deviceId = "";
            p.deviceToken = "";
            p.groupIds.clear();
            p.groups.clear();
        }
        p.serverUrl = normalized;
        p.enabled = enabled;
        p.revision++;
        save();
        return changed;
    }

    public static synchronized boolean hasIdentity() {
        RemoteModels.Profile p = get();
        return !unreadableIdentity && !TextUtils.isEmpty(p.deviceId) && !TextUtils.isEmpty(p.deviceToken);
    }

    public static synchronized void applyRegistration(RemoteModels.RegisterResponse response) {
        if (response == null || TextUtils.isEmpty(response.deviceId) || TextUtils.isEmpty(response.deviceToken)) return;
        RemoteModels.Profile p = get();
        List<String> groups = response.groupIds == null ? new ArrayList<>() : new ArrayList<>(response.groupIds);
        if (Objects.equals(p.deviceId, response.deviceId) && Objects.equals(p.deviceToken, response.deviceToken) && Objects.equals(p.groupIds, groups)) return;
        p.deviceId = response.deviceId;
        p.deviceToken = response.deviceToken;
        p.groupIds = groups;
        p.revision++;
        save();
    }

    /** Poll/ready groupIds are authoritative for the device and never carry controller group tokens. */
    public static synchronized void applyGroups(List<String> ids) {
        RemoteModels.Profile p = get();
        List<String> next = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        if (Objects.equals(p.groupIds, next)) return;
        p.groupIds = next;
        p.revision++;
        save();
    }

    public static synchronized void revokeGroup(String id) {
        RemoteModels.Profile p = get();
        if (p.groupIds.remove(id)) { p.revision++; save(); }
    }

    public static synchronized void clear() {
        cache = new RemoteModels.Profile();
        unreadableIdentity = false;
        try { Files.deleteIfExists(file().toPath()); } catch (Throwable ignored) {}
    }

    public static String deviceName() {
        String name = Device.get().getName();
        return TextUtils.isEmpty(name) ? BuildConfig.APPLICATION_ID : name;
    }
    public static String appVersion() { return BuildConfig.VERSION_NAME; }

    private static File file() {
        File dir = new File(App.get().getNoBackupFilesDir(), "remote");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }
    private static byte[] read(File source) throws Exception {
        if (!source.isFile() || source.length() < 13 || source.length() > 128 * 1024) return new byte[0];
        try (FileInputStream stream = new FileInputStream(source)) {
            byte[] bytes = new byte[(int) source.length()];
            int offset = 0, count;
            while (offset < bytes.length && (count = stream.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
            return offset == bytes.length ? bytes : new byte[0];
        }
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }
    private static String normalize(String value) { String v = value == null ? "" : value.trim(); while (v.endsWith("/")) v = v.substring(0, v.length() - 1); return v; }
    private static boolean isHttpsOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && !TextUtils.isEmpty(uri.getHost()) && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getPort() != 0 && uri.getPort() <= 65535;
        } catch (Throwable e) { return false; }
    }
}
