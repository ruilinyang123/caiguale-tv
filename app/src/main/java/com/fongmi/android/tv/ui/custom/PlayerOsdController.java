package com.fongmi.android.tv.ui.custom;

import android.net.TrafficStats;
import android.os.Process;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Util;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PlayerOsdController {

    public interface Source {
        PlayerManager getPlayer();
        String getTitle();
    }

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final DecimalFormat speed = new DecimalFormat("0.0");
    private final View root;
    private final TextView topLeft;
    private final TextView topRight;
    private final TextView bottomLeft;
    private final TextView bottomRight;
    private final ProgressBar progress;
    private final Source source;
    private final Runnable updater = this::update;
    private long lastBytes;
    private long lastTime;
    private boolean controlsVisible;
    private boolean started;

    public PlayerOsdController(View root, TextView topLeft, TextView topRight, TextView bottomLeft, TextView bottomRight, ProgressBar progress, Source source) {
        this.bottomRight = bottomRight;
        this.bottomLeft = bottomLeft;
        this.topRight = topRight;
        this.topLeft = topLeft;
        this.progress = progress;
        this.source = source;
        this.root = root;
    }

    public void start() {
        started = true;
        lastBytes = getUidRxBytes();
        lastTime = System.currentTimeMillis();
        App.post(updater, 0);
    }

    public void stop() {
        started = false;
        App.removeCallbacks(updater);
    }

    public void release() {
        stop();
    }

    public void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (started) render();
    }

    private void update() {
        render();
        if (started) App.post(updater, 1000);
    }

    private void render() {
        if (!PlayerSetting.isOsdEnabled() || controlsVisible) {
            root.setVisibility(View.GONE);
            return;
        }
        root.setVisibility(View.VISIBLE);
        PlayerManager player = source.getPlayer();
        String title = PlayerSetting.isOsdTitle() ? source.getTitle() : "";
        String resolution = PlayerSetting.isOsdResolution() && player != null ? player.getSizeText() : "";
        set(topLeft, join(title, resolution), PlayerSetting.isOsdTitle() || PlayerSetting.isOsdResolution());
        set(topRight, clock.format(new Date()), PlayerSetting.isOsdTime());
        boolean vod = player != null && !player.isLive() && player.getDuration() > 0;
        String position = vod ? Util.timeMs(player.getPosition()) + " / " + Util.timeMs(player.getDuration()) : "";
        set(bottomLeft, position, PlayerSetting.isOsdProgress() && vod);
        set(bottomRight, getSpeed(), PlayerSetting.isOsdTraffic());
        progress.setVisibility(PlayerSetting.isOsdMini() && vod ? View.VISIBLE : View.GONE);
        if (vod) {
            progress.setMax(1000);
            progress.setProgress((int) Math.min(1000, Math.max(0, player.getPosition() * 1000 / player.getDuration())));
        }
    }

    private String getSpeed() {
        long now = System.currentTimeMillis();
        long bytes = getUidRxBytes();
        long value = Math.max(0, (bytes - lastBytes) * 1000 / Math.max(1, now - lastTime));
        lastBytes = bytes;
        lastTime = now;
        if (value < 1024 * 1024) return value / 1024 + " KB/s";
        return speed.format(value / 1024f / 1024f) + " MB/s";
    }

    private long getUidRxBytes() {
        long value = TrafficStats.getUidRxBytes(Process.myUid());
        return value == TrafficStats.UNSUPPORTED ? 0 : value;
    }

    private void set(TextView view, String text, boolean visible) {
        view.setText(text);
        view.setVisibility(visible && !TextUtils.isEmpty(text) ? View.VISIBLE : View.GONE);
    }

    private String join(String first, String second) {
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second)) return first;
        return first + "\n" + second;
    }
}
