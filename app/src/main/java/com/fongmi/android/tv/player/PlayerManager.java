package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.effect.ColorLut;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardBufferPolicy;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardController;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardEligibility;
import com.fongmi.android.tv.player.exo.ExoNextEpisodePreloader;
import com.fongmi.android.tv.player.exo.ForwardBufferTrend;
import com.fongmi.android.tv.player.lut.LutEffectFactory;
import com.fongmi.android.tv.player.lut.LutEligibility;
import com.fongmi.android.tv.player.lut.LutPreset;
import com.fongmi.android.tv.player.lut.LutSetting;
import com.fongmi.android.tv.player.lut.LutStore;
import com.fongmi.android.tv.player.lut.MpvLutShader;
import com.fongmi.android.tv.player.lut.MpvLutShaderFactory;
import com.fongmi.android.tv.player.extractor.MpdEdlResolver;
import com.fongmi.android.tv.player.extractor.MpdSanitizer;
import com.fongmi.android.tv.setting.DanmakuState;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.setting.VideoSetting;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private final Runnable runnable;
    private final Callback callback;
    private final ExoNextEpisodePreloader nextEpisodePreloader = new ExoNextEpisodePreloader();
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private int playerType;

    private boolean initTrack;
    private int retry;
    private int lastListenerState = Player.STATE_IDLE;
    private int lutApplySeq;

    private final Runnable networkProtectionRunnable = this::evaluateNetworkProtection;
    private final ExoNetworkGuardController networkProtectionController = new ExoNetworkGuardController();
    private final ForwardBufferTrend networkProtectionTrend = new ForwardBufferTrend();
    private ExoNetworkGuardController.State networkProtectionState = ExoNetworkGuardController.State.NORMAL;
    private ExoNetworkGuardController.ProtectionTier networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
    private String networkProtectionReason;
    // Starts at the configured default for a fresh session, then follows the user across
    // media items: setMediaItem() must not reset it, or switching source/episode/subtitle
    // would silently drop the chosen playback speed.
    private float userPlaybackSpeed = PlayerSetting.getDefaultSpeed();
    private float networkProtectionSpeed = 1f;
    private float networkProtectionSupportedSpeed = 1f;
    private long networkProtectionMediaBitrate;
    private int networkProtectionRebufferCount;
    private int rebufferCount;
    private long rebufferTotalMs;
    private long rebufferStart;

    public PlayerManager(Callback callback) {
        this.runnable = () -> callback.onError(ResUtil.getString(R.string.error_play_timeout));
        this.playerType = PlayerSetting.getPlayer();
        this.engine = buildEngine(playerType, PlayerEngine.HARD);
        this.player = engine.getPlayer();
        this.callback = callback;
    }

    public void release() {
        if (player == null && engine == null) return;
        stopParse();
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        if (player != null) player.removeListener(listener);
        clearVideoEffect();
        clearAudioEffect();
        nextEpisodePreloader.release();
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    public Tracks getCurrentTracks() {
        return engine.getCurrentTracks();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine.isLive();
    }

    public boolean isVod() {
        return engine.isVod();
    }

    public boolean haveTrack(int type) {
        return engine.haveTrack(type);
    }

    public boolean haveTitle() {
        return engine.haveTitle();
    }

    public boolean haveChapter() {
        return false; // Official Media3 base does not provide the fork chapter API.
    }

    public boolean haveDanmaku() {
        return false; // Fork-only renderer is excluded.
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public Format getVideoFormat() {
        try {
            Tracks tracks = player == null ? null : player.getCurrentTracks();
            if (tracks != null) {
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!group.isTrackSelected(i)) continue;
                        return group.getTrackFormat(i);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public long getDroppedFrames() {
        return engine == null ? 0 : engine.getDroppedFrames();
    }

    public int getRebufferCount() {
        return rebufferCount;
    }

    public long getRebufferTotalMs() {
        return rebufferTotalMs;
    }

    private void trackRebuffer(int state) {
        if (lastListenerState == Player.STATE_READY && state == Player.STATE_BUFFERING) {
            rebufferCount++;
            rebufferStart = System.currentTimeMillis();
        } else if (state != Player.STATE_BUFFERING && rebufferStart > 0) {
            rebufferTotalMs += Math.max(0, System.currentTimeMillis() - rebufferStart);
            rebufferStart = 0;
        }
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return engine.getDecodeText();
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        setMediaItem();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        setMediaItem();
    }

    public int getPlayerType() {
        return playerType;
    }

    public String getPlayerText() {
        String[] names = ResUtil.getStringArray(R.array.select_player_kernel);
        int index = Math.min(Math.max(playerType, 0), names.length - 1);
        return names[index];
    }

    public boolean canSetVideoSetting() {
        return engine.supportsVideoEffects();
    }

    public void refreshVideoSetting() {
        if (!canSetVideoSetting()) return;
        engine.applyVideoProfile(VideoSetting.getAppliedProfile());
    }

    public void clearVideoEffect() {
        engine.clearVideoProfile();
    }

    public String getLutText() {
        return LutSetting.getButtonText();
    }

    public String getLutUnavailableReason() {
        return engine == null ? null : LutEligibility.getUnavailableReason(engine, spec);
    }

    public boolean selectLut(LutPreset preset, boolean preview) {
        if (engine == null) return false;
        if (preset != null) {
            String reason = getLutUnavailableReason();
            if (!TextUtils.isEmpty(reason)) {
                SpiderDebug.log("lut-ui", "reject preset=%s reason=%s", preset.getId(), reason);
                Notify.show(reason);
                return false;
            }
        }
        LutSetting.select(preset);
        if (preset != null && preview) applyLutPreview(true);
        else applyLut(true);
        return true;
    }

    public void applyLut(boolean notify) {
        applyLut(notify, false);
    }

    public void applyLutPreview(boolean notify) {
        applyLut(notify, true);
    }

    private void applyLut(boolean notify, boolean preview) {
        if (engine == null) return;
        int seq = ++lutApplySeq;
        if (!LutSetting.isEnabled()) {
            clearLut();
            return;
        }
        LutPreset preset = LutStore.find(LutSetting.getPresetId());
        if (preset == null) {
            clearLut();
            if (notify) Notify.show(R.string.lut_missing);
            return;
        }
        String reason = getLutUnavailableReason();
        if (!TextUtils.isEmpty(reason)) {
            clearLut();
            if (notify) Notify.show(reason);
            return;
        }
        boolean nativeLut = engine.supportsNativeLut();
        int strength = LutSetting.getStrength();
        int previewSeconds = LutSetting.getPreviewSeconds();
        Task.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                if (nativeLut) {
                    MpvLutShader shader = MpvLutShaderFactory.create(preset, strength, preview);
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "create shader preset=%s strength=%d preview=%s cost=%dms", preset.getId(), strength, preview, System.currentTimeMillis() - start);
                    App.post(() -> {
                        if (seq != lutApplySeq || engine == null) return;
                        engine.setNativeLutShader(shader);
                    });
                } else {
                    ColorLut colorLut = LutEffectFactory.createColorLut(preset, strength);
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "create preset=%s strength=%d preview=%s cost=%dms", preset.getId(), strength, preview, System.currentTimeMillis() - start);
                    App.post(() -> {
                        if (seq != lutApplySeq || engine == null) return;
                        engine.applyLut(colorLut, preview, previewSeconds);
                    });
                }
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "create failed preset=%s strength=%d error=%s", preset.getId(), strength, causeChain(e));
                App.post(() -> {
                    if (seq != lutApplySeq || engine == null) return;
                    clearLut();
                    if (notify) Notify.show(R.string.lut_apply_failed);
                });
            }
        });
    }

    private void clearLut() {
        if (engine == null) return;
        engine.setNativeLutShader(null);
        engine.clearLut();
    }

    public void applyAudioSetting() {
        if (engine != null) engine.applyAudioSetting();
    }

    public void clearAudioEffect() {
        if (engine != null) engine.clearAudioEffect();
    }

    public boolean canSetAudioSetting() {
        return engine != null && engine.supportsAudioSetting();
    }

    public int getAudioChannelCount() {
        try {
            Tracks tracks = player == null ? null : player.getCurrentTracks();
            if (tracks == null) return Format.NO_VALUE;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int i = 0; i < group.length; i++) {
                    if (!group.isTrackSelected(i)) continue;
                    Format format = group.getTrackFormat(i);
                    if (format.channelCount > 0) return format.channelCount;
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return Format.NO_VALUE;
    }

    public void togglePlayer() {
        switchPlayer(PlayerSetting.nextPlayer(playerType));
    }

    public void switchPlayer(int type) {
        type = PlayerSetting.sanitizePlayer(type);
        if (type == playerType) return;
        long position = player != null ? Math.max(0, player.getCurrentPosition()) : 0;
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        int decode = engine != null ? engine.getDecode() : PlayerEngine.HARD;
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        PlayerSetting.putPlayer(type);
        playerType = type;
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        engine.setRepeatOne(repeat);
        setSpeed(speed);
        callback.onPlayerRebuild(player);
        if (spec != null && spec.getUrl() != null) {
            try {
                setMediaItem();
                if (position > 0) seekTo(position);
            } catch (Throwable t) {
                SpiderDebug.log(t);
            }
        }
        applyLut(false);
    }

    private PlayerEngine buildEngine(int type, int decode) {
        return new ExoPlayerEngine(decode, listener);
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        artUri = ImgUtil.cache(artUri);
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        engine.setMetadata(data);
    }

    private Danmaku getSelectedDanmaku() {
        List<Danmaku> items = getDanmakus();
        if (items == null) return null;
        return items.stream().filter(Danmaku::isSelected).findFirst().orElse(null);
    }

    public boolean preloadNext(Result result, String key, MediaMetadata metadata) {
        if (playerType != PlayerSetting.EXO || result == null || result.shouldUseParse()) return false;
        return nextEpisodePreloader.preload(PlaySpec.from(result, key, metadata), playerType);
    }

    public void clearNextPreload() {
        nextEpisodePreloader.release();
    }

    public boolean supportsSecondarySubtitle() {
        return engine != null && engine.supportsSecondarySubtitle();
    }

    public void setSecondarySubtitleTrack(Track track) {
        if (engine != null) engine.setSecondarySubtitleTrack(track);
    }

    public void setSubtitleSettingStyle() {
        if (engine == null) return;
        engine.setSubtitleStyle(SubtitleSetting.getScale(App.get()), SubtitleSetting.getPosition());
    }

    public void sendDanmaku(String text) {
        // Danmaku renderer is a fork-only Media3 extension and is not shipped in the base build.
    }

    public String setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        userPlaybackSpeed = speed;
        resetNetworkProtectionSession("user-speed");
        if (Math.abs(speed - 1f) < 0.001f) scheduleNetworkProtection(0);
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    public boolean supportsSkipSilence() {
        return player instanceof ExoPlayer;
    }

    public boolean isSkipSilence() {
        return player instanceof ExoPlayer exo && exo.getSkipSilenceEnabled();
    }

    public void setSkipSilenceEnabled(boolean enabled) {
        if (player instanceof ExoPlayer exo) exo.setSkipSilenceEnabled(enabled);
    }

    private void applyEffectiveSpeed(float speed, String reason) {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return;
        float current = player.getPlaybackParameters().speed;
        float next = roundThousandth(speed);
        if (Math.abs(current - next) < 0.0005f) return;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(next));
        SpiderDebug.log("player-guard", "applyEffectiveSpeed reason=%s current=%.3f next=%.3f", reason, current, next);
    }

    private void resetNetworkProtectionSession(String reason) {
        App.removeCallbacks(networkProtectionRunnable);
        networkProtectionController.reset();
        networkProtectionTrend.reset();
        networkProtectionState = ExoNetworkGuardController.State.NORMAL;
        networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
        networkProtectionReason = reason;
        networkProtectionSpeed = 1f;
        networkProtectionSupportedSpeed = 1f;
        networkProtectionMediaBitrate = 0;
        networkProtectionRebufferCount = 0;
        applyEffectiveSpeed(userPlaybackSpeed, reason);
    }

    private ExoNetworkGuardEligibility.Decision getNetworkProtectionEligibility() {
        boolean enabled = ExoPerformanceSetting.isNetworkProtectionEnabled();
        boolean exo = playerType == PlayerSetting.EXO;
        boolean vod = isVod();
        boolean unitSpeed = Math.abs(userPlaybackSpeed - 1f) < 0.001f;
        boolean speedAvailable = player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH);
        return ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(enabled, exo, vod, unitSpeed, speedAvailable, false, false));
    }

    private void scheduleNetworkProtection(long delayMs) {
        App.removeCallbacks(networkProtectionRunnable);
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        if (!eligibility.eligible()) {
            if (networkProtectionSpeed < 0.999f) resetNetworkProtectionSession(eligibility.reason());
            else {
                networkProtectionState = ExoNetworkGuardController.State.NORMAL;
                networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
                networkProtectionReason = eligibility.reason();
            }
            return;
        }
        App.post(networkProtectionRunnable, delayMs);
    }

    private void evaluateNetworkProtection() {
        if (player == null) return;
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        boolean eligible = eligibility.eligible();
        long nowMs = SystemClock.elapsedRealtime();
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        boolean playing = player.isPlaying();
        boolean loading = player.isLoading();
        long bufferedMs = Math.max(0, player.getTotalBufferedDuration());
        networkProtectionTrend.observe(nowMs, bufferedMs, ready && playing, loading);
        ForwardBufferTrend.Snapshot trend = networkProtectionTrend.snapshot();
        float minimumSpeed = ExoPerformanceSetting.getNetworkProtectionMinimumSpeed();
        long safeBufferMs = getNetworkProtectionSafeBufferMs();
        ExoNetworkGuardController.Decision decision = networkProtectionController.evaluate(new ExoNetworkGuardController.Input(
                nowMs, eligible, ready, playing, loading, bufferedMs,
                trend.known(), trend.slopeMsPerSecond(), trend.fastSlopeMsPerSecond(), trend.slowSlopeMsPerSecond(), trend.windowMs(),
                networkProtectionRebufferCount, getSpeed(), minimumSpeed, safeBufferMs, false, 1f));
        networkProtectionState = decision.state();
        networkProtectionTier = decision.tier();
        networkProtectionReason = decision.reason();
        networkProtectionSpeed = decision.targetSpeed();
        networkProtectionSupportedSpeed = decision.supportedSpeed();
        if (decision.changed()) applyEffectiveSpeed(networkProtectionSpeed, "guard-" + decision.reason());
        if (eligible && ready && playing) scheduleNetworkProtection(getNetworkProtectionEvaluationDelayMs());
    }

    private long getNetworkProtectionEvaluationDelayMs() {
        return switch (networkProtectionState) {
            case WARNING, PROTECT, RECOVERY -> ExoNetworkGuardController.CONTROL_INTERVAL_MS;
            case NORMAL, UNSUSTAINABLE -> ExoNetworkGuardController.OBSERVE_INTERVAL_MS;
        };
    }

    private long getNetworkProtectionSafeBufferMs() {
        boolean loopback = spec != null && spec.getPlaybackRoute() != null && spec.getPlaybackRoute().loopback();
        return ExoNetworkGuardBufferPolicy.resolve(loopback, ExoPerformanceSetting.getRebufferMs());
    }

    private static float roundThousandth(float value) {
        return Math.round(value * 1_000f) / 1_000f;
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) engine.setTrack(tracks);
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        player.stop();
        stopParse();
    }

    public void clearMediaItems() {
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return engine.isRepeatOne();
    }

    public void setRepeatOne(boolean repeat) {
        engine.setRepeatOne(repeat);
    }

    public void seekTo(long time) {
        player.seekTo(time);
    }

    // 官方 Media3 不提供音视频/字幕时间轴偏移 API（原定制包新增），
    // 基础版保留调用点但固定返回 0 且不修改播放器，避免伪造已生效的偏移。
    public long getTextOffsetMs() {
        return 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        SpiderDebug.log("player", "text offset unsupported in official media3 base, ignored");
    }

    public long getAudioOffsetMs() {
        return 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        SpiderDebug.log("player", "audio offset unsupported in official media3 base, ignored");
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
    }

    public void resetTrack() {
        engine.resetTrack();
    }

    public void toggleDecode() {
        engine.setDecode(engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
        rebuildPlayer();
        setMediaItem();
    }

    private void rebuildPlayer() {
        player = engine.rebuild(listener);
        callback.onPlayerRebuild(player);
    }

    public void browse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    public void start(PlaySpec spec, long timeout) {
        this.spec = spec;
        setMediaItem(timeout);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        stopParse();
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    public void setMediaItem() {
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(long timeout) {
        if (spec == null || spec.getUrl() == null) return;
        String unsupported = BaseProtocolPolicy.unsupportedReason(spec.getUrl());
        if (unsupported != null) {
            player.stop();
            App.removeCallbacks(runnable);
            callback.onError(unsupported);
            return;
        }
        SpiderDebug.log("player", "setMediaItem timeout=%d spec=%s", timeout, debugSpec());
        applyMpdHandling(spec);
        engine.start(spec.checkUa());
        setSpeed(userPlaybackSpeed);
        App.post(runnable, timeout);
        callback.onPrepare();
        initTrack = false;
    }

    private void applyMpdHandling(PlaySpec spec) {
        String url = spec.getUrl();
        String sanitized = MpdSanitizer.sanitize(url);
        if (!sanitized.equals(url)) {
            spec.setUrl(sanitized);
            SpiderDebug.log("player", "mpd-sanitizer applied urlLen=%d", sanitized.length());
        }
        if (!isDash(sanitized)) return;
        if (MpdSanitizer.isYoutube(sanitized)) applyYoutubeUserAgent(spec);
    }

    private Map<String, String> safeHeaders(Map<String, String> headers) {
        Map<String, String> out = new HashMap<>();
        if (headers == null) return out;
        for (Map.Entry<String, String> entry : headers.entrySet()) if (entry.getValue() != null) out.put(entry.getKey(), entry.getValue());
        return out;
    }

    private void applyYoutubeUserAgent(PlaySpec spec) {
        spec.checkUa();
        for (Map.Entry<String, String> entry : spec.getHeaders().entrySet()) {
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) {
                entry.setValue("com.google.android.youtube/21.02.35 (Linux; U; Android 14) gzip");
                return;
            }
        }
    }

    private boolean isDash(String url) {
        String format = spec == null ? null : spec.getFormat();
        if (androidx.media3.common.MimeTypes.APPLICATION_MPD.equals(format) || "application/dash+xml".equalsIgnoreCase(format) || "dash".equalsIgnoreCase(format)) return true;
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.startsWith("data:application/dash+xml") || lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd");
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        SpiderDebug.log("player", "parseSuccess from=%s url=%s headerKeys=%s", from, url, headers == null ? null : headers.keySet());
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem();
    }

    @Override
    public void onParseError() {
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    private String debugSpec() {
        if (spec == null) return "null";
        return "key=" + spec.getKey() +
                ", url=" + spec.getUrl() +
                ", format=" + spec.getFormat() +
                ", headerKeys=" + (spec.getHeaders() == null ? null : spec.getHeaders().keySet()) +
                ", subs=" + (spec.getSubs() == null ? 0 : spec.getSubs().size()) +
                ", danmakus=" + (spec.getDanmakus() == null ? 0 : spec.getDanmakus().size());
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }

    private static String causeChain(Throwable error) {
        if (error == null) return "null";
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (builder.length() > 0) builder.append(" <- ");
            builder.append(current.getClass().getName());
            if (!TextUtils.isEmpty(current.getMessage())) builder.append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return builder.toString();
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
            if (lastListenerState == Player.STATE_READY && state == Player.STATE_BUFFERING) networkProtectionRebufferCount++;
            trackRebuffer(state);
            lastListenerState = state;
            SpiderDebug.log("player", "state=%s spec=%s", stateName(state), debugSpec());
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying && rebufferStart > 0) {
                rebufferTotalMs += Math.max(0, System.currentTimeMillis() - rebufferStart);
                rebufferStart = 0;
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            restoreSecondarySubtitle(tracks);
            setSubtitleSettingStyle();
            callback.onTracksChanged();
            initTrack = true;
        }

    

    

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            PlaybackErrorClassifier.Failure failure = PlaybackErrorClassifier.classify(e, getEffectivePlaybackRoute());
            PlayerEngine.ErrorAction action = engine.handleError(e);
            SpiderDebug.log("player", "error %s action=%s retry=%d spec=%s cause=%s", failure.logSummary(), action, retry, debugSpec(), causeChain(e));
            if (action == PlayerEngine.ErrorAction.RECOVERED) {
                return;
            }
            if (action == PlayerEngine.ErrorAction.FATAL) {
                callback.onError(getPlaybackErrorMessage(failure));
            } else if (++retry > 1) {
                callback.onError(getPlaybackErrorMessage(failure));
            } else {
                toggleDecode();
            }
        }
    };

    private void restoreSecondarySubtitle(Tracks tracks) {
        if (!supportsSecondarySubtitle()) return;
        int preference = SubtitleSetting.getSecondaryTrackId();
        if (preference == SubtitleSetting.SECONDARY_SUBTITLE_OFF) {
            setSecondarySubtitleTrack(Track.disabled(C.TRACK_TYPE_TEXT, ""));
            return;
        }
        Format candidate = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                if (group.isTrackSelected(i)) continue;
                if (candidate == null) candidate = format;
                if (SubtitleSetting.matchesSecondaryTrack(format)) candidate = format;
                if (SubtitleSetting.matchesSecondaryTrack(format)) break;
            }
            if (candidate != null && SubtitleSetting.matchesSecondaryTrack(candidate)) break;
        }
        if (candidate == null) return;
        boolean exactIdentity = SubtitleSetting.matchesSecondaryTrack(candidate);
        if (preference >= 0 && !exactIdentity) return;
        String name = TextUtils.isEmpty(candidate.label) ? PlayerHelper.describeFormat(candidate) : candidate.label;
        setSecondarySubtitleTrack(new Track(C.TRACK_TYPE_TEXT, name, PlayerHelper.describeFormat(candidate)).playerId(candidate.id));
        SubtitleSetting.putSecondaryTrack(candidate);
    }

    private PlaybackRoute.Resolution getEffectivePlaybackRoute() {
        PlaybackRoute.Resolution route = engine == null ? null : engine.getEffectivePlaybackRoute();
        if (route != null && route.route() != PlaybackRoute.OTHER) return route;
        return spec == null ? PlaybackRoute.resolve(null) : spec.getPlaybackRoute();
    }

    private String getPlaybackErrorMessage(PlaybackErrorClassifier.Failure failure) {
        return switch (failure.stage()) {
            case LOCAL_ENDPOINT -> switch (failure.route().owner()) {
                case APP_MAIN_SERVER, APP_HLS_PROXY -> ResUtil.getString(R.string.error_play_stage_app_local);
                default -> ResUtil.getString(R.string.error_play_stage_external_local);
            };
            case NETWORK_IO -> PlaybackRouteCapabilities.resolve(failure.route()).externalUpstreamOpaque()
                    ? ResUtil.getString(R.string.error_play_stage_external_supply)
                    : ResUtil.getString(R.string.error_play_stage_network);
            case MEDIA_PARSING -> ResUtil.getString(R.string.error_play_stage_media);
            case DECODER -> ResUtil.getString(R.string.error_play_stage_decoder);
            case OUTPUT -> ResUtil.getString(R.string.error_play_stage_output);
            case DRM -> ResUtil.getString(R.string.error_play_stage_drm);
            case UNKNOWN -> ResUtil.getString(R.string.error_play_stage_unknown);
        };
    }
}
