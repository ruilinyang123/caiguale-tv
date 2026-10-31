package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;

public record ConfigSummary(Config config, boolean current) {

    public String title() {
        if (!TextUtils.isEmpty(config.getName())) return config.getName();
        return UrlUtil.getName(config.getUrl());
    }

    public String sourceLabel() {
        String url = config.getUrl();
        if (TextUtils.isEmpty(url)) return "";
        if (url.startsWith("assets://")) return "assets";
        if (url.startsWith("file://") || url.startsWith("file:/")) return "file";
        if (url.startsWith(Path.rootPath())) return "file";
        if (url.startsWith("http://") || url.startsWith("https://")) return "url";
        return "custom";
    }

    public String subtitle() {
        String label = sourceLabel();
        if (TextUtils.isEmpty(label)) return config.getUrl();
        return label + " · " + config.getUrl();
    }
}
