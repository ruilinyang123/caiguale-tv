package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogPlayerEngineBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;

public final class PlayerEngineDialog extends BaseBottomSheetDialog {

    private DialogPlayerEngineBinding binding;
    private PlayerManager player;

    public static void show(FragmentActivity activity, PlayerManager player) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof PlayerEngineDialog) return;
        PlayerEngineDialog dialog = new PlayerEngineDialog();
        dialog.player = player;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogPlayerEngineBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 基础版只带 EXO 内核：IJK/MPV 选项隐藏，历史偏好由 sanitizePlayer() 收敛到 EXO。
        binding.mpv.setVisibility(PlayerSetting.hasMpv() ? View.VISIBLE : View.GONE);
        binding.ijk.setVisibility(PlayerSetting.hasIjk() ? View.VISIBLE : View.GONE);
        setSelected();
        getSelectedView().requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.exo.setOnClickListener(view -> selectEngine(PlayerSetting.EXO));
        binding.ijk.setOnClickListener(view -> selectEngine(PlayerSetting.IJK));
        binding.mpv.setOnClickListener(view -> selectEngine(PlayerSetting.MPV));
    }

    private void selectEngine(int engine) {
        if (player == null) PlayerSetting.putPlayer(engine);
        else player.switchPlayer(engine);
        dismiss();
    }

    private void setSelected() {
        int engine = player == null ? PlayerSetting.getPlayer() : player.getPlayerType();
        binding.exo.setSelected(engine == PlayerSetting.EXO);
        binding.ijk.setSelected(engine == PlayerSetting.IJK);
        binding.mpv.setSelected(engine == PlayerSetting.MPV);
    }

    private View getSelectedView() {
        int engine = player == null ? PlayerSetting.getPlayer() : player.getPlayerType();
        if (engine == PlayerSetting.IJK) return binding.ijk;
        if (engine == PlayerSetting.MPV) return binding.mpv;
        return binding.exo;
    }
}
