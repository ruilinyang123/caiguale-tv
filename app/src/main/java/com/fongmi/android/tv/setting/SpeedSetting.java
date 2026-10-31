package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;
import com.google.android.material.slider.Slider;

import java.util.Locale;

public class SpeedSetting {

    public static final float MIN = 0.1f;
    public static final float MAX = 5.0f;
    public static final float STEP = 0.1f;
    public static final float NORMAL = 1.0f;
    private static final float EPSILON = 0.001f;
    private static final float[] PRESETS = {0.1f, 0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 5.0f};

    public static void setup(Slider slider) {
        slider.setValueFrom(MIN);
        slider.setValueTo(MAX);
        slider.setStepSize(STEP);
        slider.setLabelFormatter(SpeedSetting::format);
    }

    public static float clamp(float speed) {
        return Math.min(Math.max(speed, MIN), MAX);
    }

    public static float[] getPresets() {
        return PRESETS.clone();
    }

    public static float getPlayback() {
        return clamp(Prefers.getFloat("play_speed", NORMAL));
    }

    public static void putPlayback(float speed) {
        Prefers.put("play_speed", clamp(speed));
    }

    public static boolean isSkipSilence() {
        return Prefers.getBoolean("speed_skip_silence");
    }

    public static void putSkipSilence(boolean enabled) {
        Prefers.put("speed_skip_silence", enabled);
    }

    public static float next(float speed) {
        float value = clamp(speed);
        for (float preset : PRESETS) if (preset > value + EPSILON) return preset;
        return MIN;
    }

    public static String format(float speed) {
        return formatValue(speed) + "x";
    }

    public static String formatValue(float speed) {
        float value = clamp(speed);
        return Math.abs(value * 10 - Math.round(value * 10)) < EPSILON
                ? String.format(Locale.getDefault(), "%.1f", value)
                : String.format(Locale.getDefault(), "%.2f", value);
    }
}
