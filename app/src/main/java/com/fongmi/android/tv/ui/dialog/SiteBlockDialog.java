package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class SiteBlockDialog {

    public static void show(FragmentActivity activity) {
        List<Site> sites = SiteBlockSetting.filter(VodConfig.get().getSites(), true);
        CharSequence[] names = new CharSequence[sites.size()];
        boolean[] checked = new boolean[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            names[i] = sites.get(i).getName();
            checked[i] = SiteBlockSetting.isBlocked(sites.get(i));
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.site_block)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> SiteBlockSetting.setBlocked(sites.get(which), isChecked))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
