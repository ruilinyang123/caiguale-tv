package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateSettingsBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.update.GithubProxy;
import com.fongmi.android.tv.update.UpdateUrl;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;

public final class UpdateSettingsDialog {

    private UpdateSettingsDialog() {
    }

    public static void show(FragmentActivity activity) {
        DialogUpdateSettingsBinding binding = DialogUpdateSettingsBinding.inflate(LayoutInflater.from(activity));
        Dialog dialog = LightDialog.create(activity, activity.getString(R.string.update_settings), binding.getRoot());
        String proxy = Setting.getUpdateGithubProxy();
        binding.mirrorSource.setOnClickListener(view -> chooseMirror(activity, binding));
        binding.updatePath.setOnClickListener(view -> choosePath(activity, binding));
        binding.githubProxy.setOnClickListener(view -> chooseGithub(activity, binding));
        binding.save.setOnClickListener(view -> save(activity, dialog, binding));
        renderMirror(activity, binding, Setting.getMirror());
        renderPath(activity, binding, Setting.getUpdatePath());
        renderGithub(activity, binding, proxy);
        // A TV has no browser to hand the download to: its prompt only ever downloads
        // in the app, so offering the choice there would mean nothing.
        if ("leanback".equals(BuildConfig.FLAVOR_mode)) binding.updatePathRow.setVisibility(View.GONE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private static void chooseMirror(FragmentActivity activity, DialogUpdateSettingsBinding binding) {
        String[] ids = {"auto", "server", "github", "cnb"};
        CharSequence[] labels = {activity.getString(R.string.update_source_auto), activity.getString(R.string.update_source_server), activity.getString(R.string.update_github), activity.getString(R.string.update_cnb)};
        String current = Setting.getMirror();
        int selected = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(current)) selected = i;
        ChoiceDialog.showSingle(activity, R.string.update_source, labels, selected, which -> {
            Setting.putMirror(ids[which]);
            renderMirror(activity, binding, ids[which]);
        });
    }

    private static void renderMirror(FragmentActivity activity, DialogUpdateSettingsBinding binding, String mirror) {
        int label = "server".equals(mirror) ? R.string.update_source_server : "github".equals(mirror) ? R.string.update_github : "cnb".equals(mirror) ? R.string.update_cnb : R.string.update_source_auto;
        binding.mirrorSource.setText(label);
    }

    private static void choosePath(FragmentActivity activity, DialogUpdateSettingsBinding binding) {
        String[] ids = {Setting.UPDATE_PATH_BROWSER, Setting.UPDATE_PATH_APP};
        CharSequence[] labels = {activity.getString(R.string.update_browser), activity.getString(R.string.update_in_app)};
        int selected = Setting.UPDATE_PATH_APP.equals(Setting.getUpdatePath()) ? 1 : 0;
        ChoiceDialog.showSingle(activity, R.string.update_path, labels, selected, which -> {
            Setting.putUpdatePath(ids[which]);
            renderPath(activity, binding, ids[which]);
        });
    }

    private static void renderPath(FragmentActivity activity, DialogUpdateSettingsBinding binding, String path) {
        binding.updatePath.setText(Setting.UPDATE_PATH_APP.equals(path) ? R.string.update_in_app : R.string.update_browser);
    }

    private static void chooseGithub(FragmentActivity activity, DialogUpdateSettingsBinding binding) {
        GithubProxy.Preset[] presets = GithubProxy.presets();
        CharSequence[] labels = new CharSequence[presets.length];
        int selected = 0;
        String current = binding.getRoot().getTag() == null ? Setting.getUpdateGithubProxy() : (String) binding.getRoot().getTag();
        for (int i = 0; i < presets.length; i++) {
            labels[i] = label(activity, presets[i].label, presets[i].id);
            if (presets[i].id.equals(current)) selected = i;
        }
        ChoiceDialog.showSingle(activity, R.string.update_github_proxy, labels, selected, which -> {
            binding.getRoot().setTag(presets[which].id);
            renderGithub(activity, binding, presets[which].id);
        });
    }

    private static String label(FragmentActivity activity, String label, String id) {
        if (GithubProxy.DIRECT.equals(id)) return activity.getString(R.string.update_proxy_direct);
        if (GithubProxy.CUSTOM.equals(id)) return activity.getString(R.string.update_proxy_custom);
        return label;
    }

    private static void renderGithub(FragmentActivity activity, DialogUpdateSettingsBinding binding, String proxyId) {
        GithubProxy.Preset preset = GithubProxy.find(proxyId);
        binding.githubProxy.setText(label(activity, preset.label, preset.id));
        boolean custom = GithubProxy.CUSTOM.equals(preset.id);
        binding.githubCustomLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        if (custom && TextUtils.isEmpty(binding.githubCustom.getText())) binding.githubCustom.setText(Setting.getUpdateGithubProxyUrl());
    }

    private static void save(FragmentActivity activity, Dialog dialog, DialogUpdateSettingsBinding binding) {
        String proxy = binding.getRoot().getTag() == null ? Setting.getUpdateGithubProxy() : (String) binding.getRoot().getTag();
        String custom = binding.githubCustom.getText() == null ? "" : binding.githubCustom.getText().toString().trim();
        if (GithubProxy.CUSTOM.equals(proxy)) {
            try {
                UpdateUrl.requireHttpsOrigin(custom);
            } catch (Exception e) {
                binding.githubCustomLayout.setError(activity.getString(R.string.update_proxy_invalid));
                return;
            }
        }
        Setting.putUpdateGithubProxy(proxy);
        Setting.putUpdateGithubProxyUrl(custom);
        Setting.putUpdateGithubProxyMode(GithubProxy.MODE_FULL_URL);
        Github.setMirror(Setting.getMirror());
        dialog.dismiss();
        Notify.show(R.string.update_settings_saved);
    }
}
