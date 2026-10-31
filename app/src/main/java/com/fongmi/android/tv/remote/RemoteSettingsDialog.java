package com.fongmi.android.tv.remote;

import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Small reachable setup surface; credentials never appear in this UI. */
public final class RemoteSettingsDialog {
    private RemoteSettingsDialog() {}

    public static void show(FragmentActivity activity) {
        RemoteModels.Profile profile = RemoteStore.snapshot();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, 0, pad, 0);

        EditText relay = new EditText(activity);
        relay.setSingleLine(true);
        relay.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        relay.setHint(R.string.remote_public_relay_hint);
        relay.setText(profile.serverUrl);
        root.addView(relay, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(activity);
        note.setText(R.string.remote_public_security_note);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        TextView pairing = new TextView(activity);
        pairing.setText(R.string.remote_public_pairing_empty);
        pairing.setVisibility(View.GONE);
        root.addView(pairing, new LinearLayout.LayoutParams(-1, -2));

        Button code = new Button(activity);
        code.setText(R.string.remote_public_pair_code);
        root.addView(code, new LinearLayout.LayoutParams(-1, -2));
        Button revoke = new Button(activity);
        revoke.setText(R.string.remote_public_revoke);
        root.addView(revoke, new LinearLayout.LayoutParams(-1, -2));
        Button disable = new Button(activity);
        disable.setText(R.string.remote_public_disable);
        root.addView(disable, new LinearLayout.LayoutParams(-1, -2));

        var dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.remote_public_title).setView(root)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.remote_public_enable, null).create();
        code.setOnClickListener(v -> {
            if (!RemoteStore.hasIdentity()) { Notify.show(R.string.remote_public_register_first); return; }
            code.setEnabled(false);
            Task.execute(() -> {
                try {
                    RemoteModels.BindCodeResponse response = new RemoteClient(RemoteStore.snapshot()).bindCode();
                    App.post(() -> {
                        code.setEnabled(true);
                        if (response != null && !TextUtils.isEmpty(response.code)) {
                            pairing.setVisibility(View.VISIBLE);
                            pairing.setText(activity.getString(R.string.remote_public_code_value, response.code));
                        } else Notify.show(R.string.remote_public_failed);
                    });
                } catch (Throwable e) {
                    App.post(() -> { code.setEnabled(true); Notify.show(R.string.remote_public_failed); });
                }
            });
        });
        revoke.setOnClickListener(v -> Task.execute(() -> {
            boolean success = false;
            try {
                if (!RemoteStore.hasIdentity()) success = true;
                else { new RemoteClient(RemoteStore.snapshot()).revoke(); success = true; }
            } catch (Throwable ignored) {}
            RemoteAgent.get().stop();
            boolean revoked = success;
            App.post(() -> {
                if (revoked) {
                    RemoteStore.clear();
                    Notify.show(R.string.remote_public_revoked);
                } else Notify.show(R.string.remote_public_revoke_failed);
            });
        }));
        disable.setOnClickListener(v -> {
            RemoteAgent.get().stop();
            try {
                RemoteStore.configure(relay.getText() == null ? "" : relay.getText().toString(), false);
                Notify.show(R.string.remote_public_disabled);
            } catch (Throwable ignored) { Notify.show(R.string.remote_public_https_required); }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            String url = relay.getText() == null ? "" : relay.getText().toString().trim();
            try {
                String normalized = RemotePolicy.origin(url);
                boolean changed = !TextUtils.equals(RemoteStore.snapshot().serverUrl, normalized);
                if (changed) RemoteAgent.get().stop();
                RemoteStore.configure(normalized, true);
                RemoteAgent.get().start();
                dialog.dismiss();
                Notify.show(R.string.remote_public_enabled);
            } catch (Throwable e) { Notify.show(R.string.remote_public_https_required); }
        }));
        dialog.show();
    }
}
