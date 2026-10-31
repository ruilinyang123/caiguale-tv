package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogAudioSettingBinding;
import com.fongmi.android.tv.databinding.ViewSettingSliderBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.effect.audio.AudioChannelMode;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.effect.audio.AudioEffectConfig;
import com.fongmi.android.tv.player.effect.audio.AudioEffectPreset;
import com.fongmi.android.tv.player.effect.audio.AudioPresetLevels;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SliderUtil;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AudioSettingDialog extends BaseBottomSheetDialog {

    private static final int LEVEL_STEP = 20;
    private static final int STABILITY_STEP = 20;
    private static final int BOOST_STEP = 100;
    private static final int PREAMP_STEP = 100;
    private static final int CENTER_GAIN_STEP = 100;
    private static final int BALANCE_STEP = 5;

    private DialogAudioSettingBinding binding;
    private PlayerManager player;
    private final List<ViewSettingSliderBinding> bandViews = new ArrayList<>();
    private AudioEffectBands bands = AudioEffectBands.STANDARD;

    public static AudioSettingDialog create() {
        return new AudioSettingDialog();
    }

    public AudioSettingDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogAudioSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected int getMaxHeight() {
        return ResUtil.getScreenHeight() / 2;
    }

    @Override
    protected void initView() {
        setupPresets();
        setupEnable();
        setupChannelMode();
        setupSwitch();
        setupOptionSliders();
        bindBands();
        updateControls();
    }

    private boolean isPlayerAvailable() {
        return player != null && !player.isReleased();
    }

    private boolean canSetAudioSetting() {
        return isPlayerAvailable() && player.canSetAudioSetting();
    }

    private int getChannelCount() {
        int count = isPlayerAvailable() ? player.getAudioChannelCount() : 2;
        return count <= 0 ? 2 : count;
    }

    private void setupPresets() {
        String[] names = ResUtil.getStringArray(R.array.audio_preset_names);
        for (int preset = 0; preset < names.length; preset++) {
            Chip chip = new Chip(requireContext());
            chip.setText(names[preset]);
            chip.setId(preset);
            binding.presetGroup.addView(chip);
        }
        binding.presetGroup.check(getAppliedPreset());
        binding.presetGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) applyPreset(checkedIds.get(0));
        });
    }

    private int getAppliedPreset() {
        return AudioSetting.isEnabled() ? AudioSetting.getPreset() : AudioEffectPreset.OFF;
    }

    private void applyPreset(int preset) {
        AudioSetting.putPreset(preset);
        binding.enable.setChecked(preset != AudioEffectPreset.OFF);
        bindBands();
        apply();
    }

    private void setupEnable() {
        binding.enable.setChecked(AudioSetting.isEnabled());
        binding.enable.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                if (AudioSetting.getPreset() == AudioEffectPreset.OFF) AudioSetting.putPreset(AudioEffectPreset.NATURAL);
            } else {
                AudioSetting.putPreset(AudioEffectPreset.OFF);
            }
            binding.presetGroup.check(getAppliedPreset());
            bindBands();
            apply();
        });
    }

    private void setupChannelMode() {
        binding.channelModeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) bindChannelMode();
            else setChannelMode(channelModeForChip(checkedIds.get(0)));
        });
    }

    private void setChannelMode(int mode) {
        if (!AudioChannelMode.isAvailable(mode, getChannelCount())) return;
        if (AudioSetting.getChannelMode() == mode) return;
        AudioSetting.putChannelMode(mode);
        apply();
    }

    private void bindChannelMode() {
        int mode = AudioChannelMode.resolve(AudioSetting.getChannelMode(), getChannelCount());
        binding.channelModeGroup.setOnCheckedStateChangeListener(null);
        binding.channelModeGroup.check(chipForChannelMode(mode));
        binding.channelModeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) bindChannelMode();
            else setChannelMode(channelModeForChip(checkedIds.get(0)));
        });
    }

    private int chipForChannelMode(int mode) {
        int chip = binding.channelAuto.getId();
        if (mode == AudioChannelMode.STEREO) chip = binding.channelStereo.getId();
        else if (mode == AudioChannelMode.MONO) chip = binding.channelMono.getId();
        else if (mode == AudioChannelMode.REVERSE) chip = binding.channelReverse.getId();
        return chip;
    }

    private int channelModeForChip(int chipId) {
        int mode = AudioChannelMode.AUTO;
        if (chipId == binding.channelStereo.getId()) mode = AudioChannelMode.STEREO;
        else if (chipId == binding.channelMono.getId()) mode = AudioChannelMode.MONO;
        else if (chipId == binding.channelReverse.getId()) mode = AudioChannelMode.REVERSE;
        return mode;
    }

    private void setupSwitch() {
        binding.loudness.setOnCheckedChangeListener(null);
        binding.loudness.setChecked(AudioSetting.isLoudnessEnabled());
        binding.loudness.setOnCheckedChangeListener((button, checked) -> {
            AudioSetting.putLoudness(checked);
            apply();
        });
    }

    private void setupOptionSliders() {
        setupOptionSlider(binding.stability, R.string.audio_effect_stability, AudioSetting.MIN_STABILITY, AudioSetting.MAX_STABILITY, STABILITY_STEP, AudioSetting.getStability(), this::formatStability, AudioSetting::putStability);
        setupOptionSlider(binding.dialogue, R.string.audio_effect_dialogue, AudioSetting.MIN_DIALOGUE, AudioSetting.MAX_DIALOGUE, 5, AudioSetting.getDialogue(), this::formatPercent, AudioSetting::putDialogue);
        setupOptionSlider(binding.boost, R.string.audio_effect_boost, AudioSetting.MIN_BOOST, AudioSetting.MAX_BOOST, BOOST_STEP, AudioSetting.getBoost(), this::formatLevel, AudioSetting::putBoost);
        setupOptionSlider(binding.preamp, R.string.audio_effect_headroom, -AudioSetting.MAX_PREAMP, -AudioSetting.MIN_PREAMP, PREAMP_STEP, -AudioSetting.getPreamp(), this::formatLevel, value -> AudioSetting.putPreamp(-Math.round(value)));
        setupOptionSlider(binding.centerGain, R.string.audio_effect_center_gain, AudioSetting.MIN_CENTER_GAIN, AudioSetting.MAX_CENTER_GAIN, CENTER_GAIN_STEP, AudioSetting.getCenterGain(), this::formatLevel, AudioSetting::putCenterGain);
        setupOptionSlider(binding.balance, R.string.audio_effect_balance, AudioSetting.MIN_BALANCE, AudioSetting.MAX_BALANCE, BALANCE_STEP, AudioSetting.getBalance(), this::formatBalance, AudioSetting::putBalance);
    }

    private void setupOptionSlider(ViewSettingSliderBinding item, int titleRes, int from, int to, int stepSize, int initial, ValueFormatter formatter, java.util.function.IntConsumer setter) {
        Slider slider = item.slider;
        item.title.setText(titleRes);
        slider.clearOnChangeListeners();
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(stepSize);
        slider.setLabelFormatter(value -> formatter.format(Math.round(value)));
        SliderUtil.setValue(slider, initial);
        item.value.setText(formatter.format(initial));
        slider.addOnChangeListener((source, value, fromUser) -> {
            if (!fromUser) return;
            float snapped = SliderUtil.snap(source, value);
            item.value.setText(formatter.format(Math.round(snapped)));
            setter.accept(Math.round(snapped));
            apply();
        });
    }

    private void bindBands() {
        bandViews.clear();
        binding.bands.removeAllViews();
        short[] levels = AudioSetting.isEnabled() ? AudioSetting.getLevels(bands) : AudioPresetLevels.of(AudioEffectPreset.OFF, bands);
        LayoutInflater inflater = LayoutInflater.from(binding.bands.getContext());
        int stepSize = getStepSize();
        for (int i = 0; i < bands.getCount(); i++) {
            ViewSettingSliderBinding item = ViewSettingSliderBinding.inflate(inflater, binding.bands, false);
            setupBandSlider(item, i, levels[i], stepSize);
            bandViews.add(item);
            binding.bands.addView(item.getRoot());
        }
    }

    private void setupBandSlider(ViewSettingSliderBinding item, int index, short initial, int stepSize) {
        Slider slider = item.slider;
        item.title.setText(formatFrequency(bands.getCenterFrequency(index)));
        slider.clearOnChangeListeners();
        slider.setValueFrom(bands.getMinLevel());
        slider.setValueTo(bands.getMaxLevel());
        slider.setStepSize(stepSize);
        slider.setLabelFormatter(value -> formatLevel(value));
        SliderUtil.setValue(slider, bands.snapToStep(initial, stepSize));
        item.value.setText(formatLevel(slider.getValue()));
        slider.addOnChangeListener((source, value, fromUser) -> {
            if (!fromUser) return;
            float snapped = SliderUtil.snap(source, value);
            item.value.setText(formatLevel(snapped));
            short[] levels = getCurrentLevels(stepSize);
            levels[index] = bands.snapToStep(Math.round(snapped), stepSize);
            AudioSetting.putCustomLevels(bands, levels);
            switchToCustom();
            apply();
        });
    }

    private void switchToCustom() {
        if (AudioSetting.isEnabled() && AudioSetting.getPreset() == AudioEffectPreset.CUSTOM) return;
        AudioSetting.putPreset(AudioEffectPreset.CUSTOM);
        binding.enable.setChecked(true);
        binding.presetGroup.check(getAppliedPreset());
    }

    private short[] getCurrentLevels(int stepSize) {
        short[] levels = new short[bandViews.size()];
        for (int i = 0; i < bandViews.size(); i++) levels[i] = bands.snapToStep(Math.round(bandViews.get(i).slider.getValue()), stepSize);
        return levels;
    }

    private int getStepSize() {
        int range = bands.getMaxLevel() - bands.getMinLevel();
        return range > 0 && range % LEVEL_STEP == 0 ? LEVEL_STEP : 0;
    }

    private void updateControls() {
        boolean supported = canSetAudioSetting();
        binding.unsupported.setVisibility(supported ? GONE : VISIBLE);
        binding.enable.setEnabled(supported);
        binding.presetGroup.setEnabled(supported);
        int channelCount = getChannelCount();
        boolean channelMix = AudioChannelMode.isAvailable(channelCount);
        boolean centerGain = AudioEffectConfig.isCenterGainAvailable(channelCount);
        boolean balance = channelMix && AudioChannelMode.canBalance(AudioSetting.getChannelMode());
        binding.channelSection.setVisibility(channelMix ? VISIBLE : GONE);
        binding.channelStereo.setVisibility(channelCount > 2 ? VISIBLE : GONE);
        binding.centerGain.getRoot().setVisibility(centerGain ? VISIBLE : GONE);
        binding.balance.getRoot().setVisibility(balance ? VISIBLE : GONE);
        if (channelMix) bindChannelMode();
    }

    private void apply() {
        if (isPlayerAvailable()) player.applyAudioSetting();
        updateControls();
    }

    private String formatFrequency(int milliHz) {
        int hz = milliHz / 1000;
        return hz >= 1000 ? String.format(Locale.getDefault(), "%.1f kHz", hz / 1000.0f) : String.format(Locale.getDefault(), "%d Hz", hz);
    }

    private String formatLevel(float milliBel) {
        return String.format(Locale.getDefault(), "%+.1f dB", milliBel / 100.0f);
    }

    private String formatPercent(int value) {
        return String.format(Locale.getDefault(), "%d%%", value);
    }

    private String formatStability(int value) {
        String[] levels = binding.getRoot().getResources().getStringArray(R.array.audio_effect_stability_levels);
        int index = Math.round(value / (float) STABILITY_STEP);
        index = Math.clamp(index, 0, levels.length - 1);
        return levels[index];
    }

    private String formatBalance(int value) {
        if (value == 0) return binding.getRoot().getContext().getString(R.string.audio_effect_balance_center);
        if (value < 0) return binding.getRoot().getContext().getString(R.string.audio_effect_balance_left, Math.abs(value));
        return binding.getRoot().getContext().getString(R.string.audio_effect_balance_right, value);
    }

    private interface ValueFormatter {

        String format(int value);
    }
}
