package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSpeedSettingBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.utils.SliderUtil;
import com.google.android.material.slider.Slider;

public final class SpeedSettingDialog extends BaseBottomSheetDialog {

    private DialogSpeedSettingBinding binding;
    private PlayerManager player;
    private float speed;

    public static void show(FragmentActivity activity, PlayerManager player) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof SpeedSettingDialog) return;
        SpeedSettingDialog dialog = new SpeedSettingDialog();
        dialog.player = player;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSpeedSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        speed = getSpeed();
        bindSlider();
        bindPresets();
        bindReset();
        bindSkipSilence();
        updateValue();
    }

    @Override
    protected void initEvent() {
        binding.reset.setOnClickListener(view -> setSpeed(SpeedSetting.NORMAL));
    }

    private void bindSlider() {
        Slider slider = binding.speed.slider;
        binding.speed.title.setText(R.string.speed_setting_current);
        SpeedSetting.setup(slider);
        SliderUtil.setValue(slider, speed);
        slider.addOnChangeListener((source, value, fromUser) -> {
            if (fromUser) setSpeed(value);
        });
    }

    private void bindPresets() {
        float[] presets = SpeedSetting.getPresets();
        TextView[] views = getPresetViews();
        for (int i = 0; i < views.length; i++) {
            boolean visible = i < presets.length;
            views[i].setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) views[i].setText(SpeedSetting.formatValue(presets[i]));
            final float preset = visible ? presets[i] : 0;
            views[i].setOnClickListener(view -> setSpeed(preset));
        }
    }

    private void bindReset() {
        binding.reset.setNextFocusDownId(binding.speed.slider.getId());
    }

    private void bindSkipSilence() {
        boolean supported = player != null && !player.isReleased() && player.supportsSkipSilence();
        binding.skipSilenceRow.setVisibility(supported ? View.VISIBLE : View.GONE);
        binding.skipSilenceSwitch.setChecked(supported ? player.isSkipSilence() : SpeedSetting.isSkipSilence());
        binding.skipSilenceSwitch.setOnCheckedChangeListener((button, checked) -> {
            SpeedSetting.putSkipSilence(checked);
            if (supported) player.setSkipSilenceEnabled(checked);
        });
    }

    private TextView[] getPresetViews() {
        return new TextView[]{
                binding.preset01, binding.preset02, binding.preset03, binding.preset04,
                binding.preset05, binding.preset06, binding.preset07, binding.preset08,
                binding.preset09, binding.preset10, binding.preset11, binding.preset12
        };
    }

    private float getSpeed() {
        if (player == null || player.isReleased()) return SpeedSetting.getPlayback();
        return player.getSpeed();
    }

    private void setSpeed(float value) {
        speed = SpeedSetting.clamp(value);
        SpeedSetting.putPlayback(speed);
        if (player != null && !player.isReleased()) player.setSpeed(speed);
        updateValue();
    }

    private void updateValue() {
        binding.speed.value.setText(SpeedSetting.format(speed));
        SliderUtil.setValue(binding.speed.slider, speed);
    }
}
