package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingDecodeBinding;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.ResUtil;

public class SettingDecodeFragment extends BaseFragment {

    private FragmentSettingDecodeBinding mBinding;

    public static SettingDecodeFragment newInstance() {
        return new SettingDecodeFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingDecodeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setVisible();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.aac.setOnClickListener(this::setAAC);
        mBinding.tunnel.setOnClickListener(this::setTunnel);
        mBinding.audioPrefer.setOnClickListener(this::setAudioPrefer);
        mBinding.videoPrefer.setOnClickListener(this::setVideoPrefer);
        mBinding.audioPassThrough.setOnClickListener(this::setAudioPassThrough);
        mBinding.dolbyVisionOutput.setOnClickListener(this::setDolbyVisionOutput);
    }

    private void setVisible() {
        boolean exo = PlayerSetting.getPlayer() == PlayerSetting.EXO;
        boolean mpv = PlayerSetting.getPlayer() == PlayerSetting.MPV;
        mBinding.aac.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.tunnel.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.audioPrefer.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.videoPrefer.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.audioPassThrough.setVisibility(mpv ? View.VISIBLE : View.GONE);
        mBinding.dolbyVisionOutput.setVisibility(mpv ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        mBinding.aacText.setText(getSwitch(DecodeSetting.isPreferAAC()));
        mBinding.tunnelText.setText(getSwitch(DecodeSetting.isTunnel()));
        mBinding.audioPreferText.setText(getSwitch(DecodeSetting.isAudioPrefer()));
        mBinding.videoPreferText.setText(getSwitch(DecodeSetting.isVideoPrefer()));
        mBinding.audioPassThroughText.setText(getSwitch(DecodeSetting.isAudioPassThrough()));
        mBinding.dolbyVisionOutputText.setText(ResUtil.getStringArray(R.array.select_dolby_vision_output)[DecodeSetting.getDolbyVisionOutputPolicy()]);
    }

    private void setDolbyVisionOutput(View view) {
        int mode = (DecodeSetting.getDolbyVisionOutputPolicy() + 1) % ResUtil.getStringArray(R.array.select_dolby_vision_output).length;
        DecodeSetting.putDolbyVisionOutputPolicy(mode);
        mBinding.dolbyVisionOutputText.setText(ResUtil.getStringArray(R.array.select_dolby_vision_output)[mode]);
    }

    private void setTunnel(View view) {
        if (PlayerSetting.getPlayer() == PlayerSetting.MPV) return;
        DecodeSetting.putTunnel(!DecodeSetting.isTunnel());
        mBinding.tunnelText.setText(getSwitch(DecodeSetting.isTunnel()));
    }

    private void setAudioPassThrough(View view) {
        DecodeSetting.putAudioPassThrough(!DecodeSetting.isAudioPassThrough());
        mBinding.audioPassThroughText.setText(getSwitch(DecodeSetting.isAudioPassThrough()));
    }

    private void setAudioPrefer(View view) {
        DecodeSetting.putAudioPrefer(!DecodeSetting.isAudioPrefer());
        mBinding.audioPreferText.setText(getSwitch(DecodeSetting.isAudioPrefer()));
    }

    private void setVideoPrefer(View view) {
        DecodeSetting.putVideoPrefer(!DecodeSetting.isVideoPrefer());
        mBinding.videoPreferText.setText(getSwitch(DecodeSetting.isVideoPrefer()));
    }

    private void setAAC(View view) {
        DecodeSetting.putPreferAAC(!DecodeSetting.isPreferAAC());
        mBinding.aacText.setText(getSwitch(DecodeSetting.isPreferAAC()));
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
