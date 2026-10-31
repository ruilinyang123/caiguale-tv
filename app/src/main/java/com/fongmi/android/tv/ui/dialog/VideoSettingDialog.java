package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogVideoSettingBinding;
import com.fongmi.android.tv.databinding.ViewSettingSliderBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.effect.video.VideoEffectPreset;
import com.fongmi.android.tv.player.effect.video.VideoEffectProfile;
import com.fongmi.android.tv.setting.VideoSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SliderUtil;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.util.Locale;
import java.util.function.Consumer;

public final class VideoSettingDialog extends BaseBottomSheetDialog {

    private DialogVideoSettingBinding binding;
    private PlayerManager player;

    public static VideoSettingDialog create() {
        return new VideoSettingDialog();
    }

    public VideoSettingDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogVideoSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected int getMaxHeight() {
        return ResUtil.getScreenHeight() / 2;
    }

    @Override
    protected void initView() {
        setupPresets();
        setupEnable();
        setupSliders();
        binding.reset.setOnClickListener(this::onReset);
        updateUnsupported();
    }

    private void setupPresets() {
        String[] names = ResUtil.getStringArray(R.array.video_preset_names);
        for (int preset = 0; preset < names.length; preset++) {
            Chip chip = new Chip(requireContext());
            chip.setText(names[preset]);
            chip.setId(preset);
            binding.presetGroup.addView(chip);
        }
        binding.presetGroup.check(getAppliedPreset());
        binding.presetGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) binding.presetGroup.check(getAppliedPreset());
            else {
                VideoSetting.putPreset(checkedIds.get(0));
                binding.enable.setChecked(VideoSetting.isEnabled());
                setupSliders();
                refresh();
            }
        });
    }

    private int getAppliedPreset() {
        return VideoSetting.isEnabled() ? VideoSetting.getPreset() : VideoEffectPreset.OFF;
    }

    private void setupEnable() {
        binding.enable.setChecked(VideoSetting.isEnabled());
        binding.enable.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                if (VideoSetting.getPreset() == VideoEffectPreset.OFF) VideoSetting.putPreset(VideoEffectPreset.CUSTOM);
            } else {
                VideoSetting.putPreset(VideoEffectPreset.OFF);
            }
            binding.presetGroup.check(getAppliedPreset());
            refresh();
        });
    }

    private void setupSliders() {
        VideoEffectProfile profile = VideoSetting.getProfile();
        setupSlider(binding.saturation, R.string.video_effect_saturation, VideoSetting.MIN_SATURATION, VideoSetting.MAX_SATURATION, 0.01f, profile.getSaturation(), "%.2f", VideoSetting::putSaturation);
        setupSlider(binding.contrast, R.string.video_effect_contrast, VideoSetting.MIN_CONTRAST, VideoSetting.MAX_CONTRAST, 0.01f, profile.getContrast(), "%.2f", VideoSetting::putContrast);
        setupSlider(binding.brightness, R.string.video_effect_brightness, VideoSetting.MIN_BRIGHTNESS, VideoSetting.MAX_BRIGHTNESS, 0.005f, profile.getBrightness(), "%+.3f", VideoSetting::putBrightness);
        setupSlider(binding.gamma, R.string.video_effect_gamma, VideoSetting.MIN_GAMMA, VideoSetting.MAX_GAMMA, 0.01f, profile.getGamma(), "%.2f", VideoSetting::putGamma);
        setupSlider(binding.hue, R.string.video_effect_hue, VideoSetting.MIN_HUE, VideoSetting.MAX_HUE, 1.0f, profile.getHue(), "%+.0f", VideoSetting::putHue);
        setupSlider(binding.temperature, R.string.video_effect_temperature, VideoSetting.MIN_TEMPERATURE, VideoSetting.MAX_TEMPERATURE, 1.0f, profile.getTemperature(), "%+.0f", VideoSetting::putTemperature);
        setupSlider(binding.sharpness, R.string.video_effect_sharpness, VideoSetting.MIN_SHARPNESS, VideoSetting.MAX_SHARPNESS, 0.01f, profile.getSharpness(), "%.2f", VideoSetting::putSharpness);
        setupSlider(binding.shadow, R.string.video_effect_shadow, VideoSetting.MIN_SHADOW, VideoSetting.MAX_SHADOW, 0.01f, profile.getShadowLift(), "%.2f", VideoSetting::putShadow);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupSlider(ViewSettingSliderBinding item, int titleRes, float from, float to, float step, float initial, String format, Consumer<Float> setter) {
        item.title.setText(titleRes);
        Slider slider = item.slider;
        float clamped = SliderUtil.snap(initial, from, to, step);
        slider.clearOnChangeListeners();
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(step);
        slider.setLabelFormatter(value -> format(value, format));
        SliderUtil.setValue(slider, clamped);
        item.value.setText(format(clamped, format));
        slider.addOnChangeListener((source, value, fromUser) -> {
            if (!fromUser) return;
            float snapped = SliderUtil.snap(source, value);
            setter.accept(snapped);
            item.value.setText(format(snapped, format));
            switchToCustom();
            refresh();
        });
    }

    private void switchToCustom() {
        if (VideoSetting.getPreset() == VideoEffectPreset.CUSTOM) return;
        VideoSetting.putPreset(VideoEffectPreset.CUSTOM);
        binding.enable.setChecked(true);
    }

    private void onReset(View view) {
        VideoSetting.reset();
        binding.enable.setChecked(false);
        binding.presetGroup.check(getAppliedPreset());
        setupSliders();
        refresh();
    }

    private void updateUnsupported() {
        boolean supported = player != null && !player.isReleased() && player.canSetVideoSetting();
        binding.unsupported.setVisibility(supported ? GONE : VISIBLE);
        binding.enable.setEnabled(supported);
        binding.reset.setEnabled(supported);
        binding.presetGroup.setEnabled(supported);
    }

    private void refresh() {
        if (player != null && !player.isReleased()) player.refreshVideoSetting();
    }

    private String format(float value, String format) {
        return String.format(Locale.getDefault(), format, value);
    }
}
