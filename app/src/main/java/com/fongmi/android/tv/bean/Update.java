package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;

public class Update {

    public static final String CHANNEL_STABLE = "stable";

    public String channel;
    public String name;
    public String versionName;
    public String desc;
    public String apk;
    public String apkUrl;
    public String githubUrl;
    public String cnbUrl;
    public String error;
    public String sha256;
    public int code;
    public long size;
    public boolean cnb = true;

    public static Update empty(String channel) {
        Update update = new Update();
        update.channel = channel;
        return update;
    }

    public boolean hasManifest() {
        return !TextUtils.isEmpty(name) && (!TextUtils.isEmpty(githubUrl) || !TextUtils.isEmpty(cnbUrl));
    }

    public boolean hasUpdate() {
        if (!hasManifest()) return false;
        return code > BuildConfig.VERSION_CODE;
    }

    public String getText() {
        return TextUtils.isEmpty(desc) ? "" : desc;
    }
}
