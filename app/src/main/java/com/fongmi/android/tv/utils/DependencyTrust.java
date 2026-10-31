package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.os.Looper;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.event.RefreshEvent;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DependencyTrust {

    private static final String PREFIX = "dependency_trust_";
    private static final String PLAY_PREFIX = "play_trust_";
    // These checks run on loader threads, and at cold start there may be no resumed
    // Activity yet — the splash is being replaced, or the first-run permission flow
    // sent the user into Settings, which clears App.activity(). Refusing right away
    // is indistinguishable from a user "deny" and used to leave the caller with a
    // permanently broken site, so wait for an Activity instead.
    private static final long ACTIVITY_WAIT_MS = 30000;
    private static final long ACTIVITY_POLL_MS = 200;
    // A refusal is remembered for this process only: nothing is persisted, so a
    // restart asks again, but the home page's automatic retries cannot turn a
    // "deny" into a prompt every few seconds.
    private static final java.util.Set<String> DENIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean confirmPlay(String origin, String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (TextUtils.isEmpty(origin)) return false;
        String key = PLAY_PREFIX + sha256(origin);
        if (Prefers.getBoolean(key, false)) return true;
        if (DENIED.contains(key)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        Activity activity = awaitActivity();
        if (activity == null) return false;
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = new boolean[]{false};
        App.post(() -> new MaterialAlertDialogBuilder(activity)
                .setTitle("播放确认")
                .setMessage("来源：\n" + origin + "\n\n将播放：\n" + url + "\n\n仅信任你确认来源的页面。")
                .setNegativeButton("拒绝", (dialog, which) -> {
                    DENIED.add(key);
                    latch.countDown();
                })
                .setPositiveButton("信任并播放", (dialog, which) -> {
                    Prefers.put(key, true);
                    result[0] = true;
                    latch.countDown();
                })
                .setOnCancelListener(dialog -> {
                    DENIED.add(key);
                    latch.countDown();
                })
                .show());
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    /**
     * Waits for a resumed Activity to host the confirmation dialog. Returns null when
     * none shows up in time (for example the app is in the background), which the
     * callers must treat as "not confirmed yet" rather than as a refusal: the decision
     * is not persisted either way, so the next attempt asks again.
     */
    private static Activity awaitActivity() {
        long deadline = System.currentTimeMillis() + ACTIVITY_WAIT_MS;
        while (true) {
            Activity activity = App.activity();
            if (activity != null && !activity.isFinishing()) return activity;
            if (System.currentTimeMillis() >= deadline) return null;
            try {
                Thread.sleep(ACTIVITY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static boolean confirm(String type, String url, String hashType, String hash, File file) {
        if (TextUtils.isEmpty(url)) return false;
        if (TextUtils.isEmpty(hash)) return false;
        String key = key(type, url, hashType, hash);
        if (Prefers.getBoolean(key, false)) return true;
        if (DENIED.contains(key)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        Activity activity = awaitActivity();
        if (activity == null) return false;
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = new boolean[]{false};
        App.post(() -> new MaterialAlertDialogBuilder(activity)
                .setTitle("远程依赖确认")
                .setMessage(message(type, url, hashType, hash, file))
                .setNegativeButton("拒绝", (dialog, which) -> {
                    DENIED.add(key);
                    latch.countDown();
                })
                .setPositiveButton("信任并加载", (dialog, which) -> {
                    Prefers.put(key, true);
                    result[0] = true;
                    latch.countDown();
                    // The call that is waiting on this decision may already have blown
                    // its timeout while the dialog was up, so ask the UI to reload the
                    // home content: the site can finally load its spider now.
                    RefreshEvent.home();
                })
                .setOnCancelListener(dialog -> {
                    DENIED.add(key);
                    latch.countDown();
                })
                .show());
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private static String key(String type, String url, String hashType, String hash) {
        String source = VodConfig.getUrl();
        String raw = source + "|" + type + "|" + url + "|" + hashType + "|" + hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return PREFIX + sb.substring(0, 32);
        } catch (Exception e) {
            return PREFIX + Integer.toHexString(raw.hashCode());
        }
    }

    private static String message(String type, String url, String hashType, String hash, File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("类型：").append(type).append('\n');
        sb.append("配置源：").append(VodConfig.getUrl()).append('\n');
        sb.append("URL：").append(url).append('\n');
        sb.append("Hash：").append(hashType).append(':').append(hash).append('\n');
        if (file != null && file.exists()) sb.append("大小：").append(file.length()).append(" bytes\n");
        sb.append('\n').append("仅信任你确认来源的远程代码。");
        return sb.toString();
    }
}
