package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private String title;
    private String desc;

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog title(String title) {
        this.title = title;
        return this;
    }

    public UpdateDialog desc(String desc) {
        this.desc = desc;
        return this;
    }

    /**
     * Accepted for API parity with the phone dialog: Updater builds both. A TV has no
     * browser to hand the transfer off to, so the link is not shown here.
     */
    public UpdateDialog link(String link) {
        return this;
    }

    public UpdateDialog listener(UpdateListener listener) {
        this.listener = listener;
        return this;
    }

    public UpdateDialog show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.version.setText(title);
        binding.desc.setText(desc);
    }

    @Override
    protected void initEvent() {
        binding.confirm.setOnClickListener(this::onConfirm);
        binding.cancel.setOnClickListener(this::onCancel);
    }

    public void setProgress(int progress) {
        binding.confirm.setText(String.format(Locale.getDefault(), "%1$d%%", progress));
    }

    public void setProgress(int progress, long speed) {
        String text = progress < 0 ? ResUtil.getString(R.string.update_downloading) : String.format(Locale.getDefault(), "%1$d%% · %2$s", progress, formatSpeed(speed));
        binding.confirm.setText(text);
    }

    private String formatSpeed(long speed) {
        if (speed <= 0) return "";
        if (speed >= 1024 * 1024) return String.format(Locale.getDefault(), "%.1fMB/s", speed / 1024f / 1024f);
        return String.format(Locale.getDefault(), "%.0fKB/s", speed / 1024f);
    }

    private void onConfirm(View view) {
        listener.onConfirm(view);
    }

    private void onCancel(View view) {
        listener.onCancel(view);
    }
}
