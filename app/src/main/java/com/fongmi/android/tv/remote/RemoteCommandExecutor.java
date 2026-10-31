package com.fongmi.android.tv.remote;

import android.app.Activity;
import android.net.Uri;
import android.os.Looper;
import android.text.TextUtils;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.remote.RemoteModels.Command;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RemoteCommandExecutor {
    private static final Set<String> CONTROL = new HashSet<>(Arrays.asList("play", "pause", "stop", "prev", "next", "repeat", "loop", "replay"));
    private RemoteCommandExecutor() {}

    public static JsonObject execute(Command command) {
        if (command == null || TextUtils.isEmpty(command.id)) return failure("invalid command");
        if (command.expiresAt > 0 && System.currentTimeMillis() > command.expiresAt) return failure("expired");
        if ("device.status".equals(command.type)) return onMain(RemoteCommandExecutor::statusOnMain);
        if ("action.search".equals(command.type)) {
            String word = string(command.payload, "word");
            if (word.length() == 0 || word.length() > 200) return failure("invalid word");
            return onMain(() -> {
                Activity activity = App.activity();
                if (!(activity instanceof FragmentActivity)) return failure("app is not ready");
                SearchActivity.start(activity, word);
                return success();
            });
        }
        if ("action.push".equals(command.type)) {
            String url = string(command.payload, "url");
            if (!isRelayUrl(url) || url.getBytes(StandardCharsets.UTF_8).length > 4096) return failure("invalid url");
            return onMain(() -> {
                Activity activity = App.activity();
                if (!(activity instanceof FragmentActivity)) return failure("app is not ready");
                VideoActivity.push((FragmentActivity) activity, url);
                return success();
            });
        }
        if ("action.control".equals(command.type)) {
            String action = string(command.payload, "action");
            if (!CONTROL.contains(action)) return failure("invalid control action");
            return onMain(() -> controlOnMain(action));
        }
        return failure("unsupported command");
    }

    private static JsonObject controlOnMain(String action) {
        PlaybackService service = Server.get().getService();
        if (service == null || service.player() == null || service.player().isReleased()) return failure("player unavailable");
        PlayerManager player = service.player();
        switch (action) {
            case "play" -> player.play();
            case "pause" -> player.pause();
            case "stop" -> service.dispatchStop();
            case "prev" -> service.dispatchPrev();
            case "next" -> service.dispatchNext();
            case "repeat", "loop" -> service.dispatchRepeat();
            case "replay" -> service.dispatchReplay();
        }
        return success();
    }

    private static JsonObject statusOnMain() {
        PlaybackService service = Server.get().getService();
        PlayerManager player = service == null ? null : service.player();
        JsonObject data = new JsonObject();
        if (player == null || player.isReleased()) {
            data.addProperty("state", 1);
            data.addProperty("position", 0L);
            data.addProperty("duration", 0L);
            return data;
        }
        int state = player.isPlaying() ? 3 : stateNumber(player.getPlaybackState());
        data.addProperty("state", state);
        data.addProperty("position", Math.max(0L, player.getPosition()));
        data.addProperty("duration", Math.max(0L, player.getDuration()));
        return data;
    }

    private static int stateNumber(int state) {
        if (state == Player.STATE_BUFFERING) return 6;
        if (state == Player.STATE_READY) return 2;
        return 1;
    }

    private static JsonObject onMain(java.util.concurrent.Callable<JsonObject> action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return action.call(); } catch (Exception e) { return failure("command failed"); }
        }
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject[] result = new JsonObject[1];
        App.post(() -> {
            try { result[0] = action.call(); }
            catch (Exception e) { result[0] = failure("command failed"); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) return failure("main thread timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("interrupted");
        }
        return result[0] == null ? failure("command failed") : result[0];
    }

    private static JsonObject success() { JsonObject o = new JsonObject(); o.addProperty("accepted", true); return o; }
    private static JsonObject failure(String message) { JsonObject o = new JsonObject(); o.addProperty("error", message); return o; }
    private static String string(JsonObject object, String key) { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : ""; }
    private static boolean isRelayUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && !TextUtils.isEmpty(uri.getHost()) && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (Throwable e) { return false; }
    }
}
