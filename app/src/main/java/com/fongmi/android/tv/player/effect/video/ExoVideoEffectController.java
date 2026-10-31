package com.fongmi.android.tv.player.effect.video;

import androidx.media3.common.Effect;
import androidx.media3.effect.ColorLut;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.player.lut.DynamicLutEffect;

import java.util.List;

public final class ExoVideoEffectController {

    private final ColorToneAdjustEffect colorTone;
    private final DetailAdjustEffect detail;
    private final DynamicLutEffect lut;
    private boolean configured;
    private boolean lutConfigured;

    public ExoVideoEffectController() {
        this.colorTone = new ColorToneAdjustEffect();
        this.detail = new DetailAdjustEffect();
        this.lut = new DynamicLutEffect();
    }

    private List<Effect> buildEffects() {
        if (configured && lutConfigured) return List.of(colorTone, detail, lut);
        if (configured) return List.of(colorTone, detail);
        if (lutConfigured) return List.of(lut);
        return List.of();
    }

    public void apply(ExoPlayer player, VideoEffectProfile profile) {
        if (!configured && profile.isNoOp() && !lutConfigured) return;
        colorTone.setProfile(profile);
        detail.setProfile(profile);
        configured = true;
        player.setVideoEffects(buildEffects());
    }

    public void clear(ExoPlayer player) {
        if (!configured) return;
        configured = false;
        player.setVideoEffects(buildEffects());
    }

    public boolean applyLut(ExoPlayer player, ColorLut colorLut, boolean preview, int previewSeconds) {
        lut.set(colorLut, preview, previewSeconds);
        lutConfigured = true;
        player.setVideoEffects(buildEffects());
        return true;
    }

    public void clearLut(ExoPlayer player) {
        if (!lutConfigured) return;
        lut.clear();
        lutConfigured = false;
        player.setVideoEffects(buildEffects());
    }
}
