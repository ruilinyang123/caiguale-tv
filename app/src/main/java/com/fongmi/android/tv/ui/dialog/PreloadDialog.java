package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogPreloadBinding;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.SliderUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class PreloadDialog extends BaseAlertDialog {

    public static final int THREADS = 0;
    public static final int SIZE = 1;
    public static final int TIME = 2;

    private DialogPreloadBinding binding;
    private int type;

    public static void show(FragmentActivity activity, int type) {
        PreloadDialog dialog = new PreloadDialog();
        Bundle args = new Bundle();
        args.putInt("type", type);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogPreloadBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        type = requireArguments().getInt("type");
        binding.title.setText(getTitle());
        binding.slider.setValueFrom(getMin());
        binding.slider.setValueTo(getMax());
        binding.slider.setStepSize(getStep());
        binding.slider.setLabelFormatter(this::format);
        SliderUtil.setValue(binding.slider, getValue());
    }

    @Override
    protected void initEvent() {
        binding.slider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) save(Math.round(SliderUtil.snap(slider, value)));
        });
        binding.slider.setOnKeyListener((view, keyCode, event) -> {
            boolean enter = KeyUtil.isEnterKey(event);
            if (enter) dismiss();
            return enter;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private int getTitle() {
        if (type == THREADS) return com.fongmi.android.tv.R.string.player_preload_threads;
        if (type == SIZE) return com.fongmi.android.tv.R.string.player_preload_size;
        return com.fongmi.android.tv.R.string.player_preload_time;
    }

    private int getMin() {
        if (type == THREADS) return PreloadSetting.MIN_THREADS;
        if (type == SIZE) return PreloadSetting.MIN_SIZE_MB;
        return PreloadSetting.MIN_TIME_SECONDS;
    }

    private int getMax() {
        if (type == THREADS) return PreloadSetting.MAX_THREADS;
        if (type == SIZE) return PreloadSetting.MAX_SIZE_MB;
        return PreloadSetting.MAX_TIME_SECONDS;
    }

    private int getStep() {
        if (type == THREADS) return 1;
        if (type == SIZE) return 128;
        return PreloadSetting.STEP_TIME_SECONDS;
    }

    private int getValue() {
        if (type == THREADS) return PreloadSetting.getPreloadThreads();
        if (type == SIZE) return PreloadSetting.getPreloadSizeMb();
        return PreloadSetting.getPreloadTimeSeconds();
    }

    private void save(int value) {
        if (type == THREADS) PreloadSetting.putPreloadThreads(value);
        else if (type == SIZE) PreloadSetting.putPreloadSizeMb(value);
        else PreloadSetting.putPreloadTimeSeconds(value);
    }

    private String format(float value) {
        int v = Math.round(value);
        if (type == THREADS) return getString(com.fongmi.android.tv.R.string.player_preload_threads_value, v);
        if (type == SIZE) return v + " MB";
        return getString(com.fongmi.android.tv.R.string.player_preload_time_value, v);
    }
}
