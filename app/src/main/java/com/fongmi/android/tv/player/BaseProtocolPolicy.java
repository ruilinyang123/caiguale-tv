package com.fongmi.android.tv.player;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Base build protocol policy. Reject removed SDK protocols before extraction or playback. */
public final class BaseProtocolPolicy {
    private static final Set<String> REMOVED = Set.of(
            "mitv", "tvbus", "jianpian", "tvbox-xg", "xg", "xgplay", "ftp",
            "magnet", "ed2k", "thunder", "bdmv", "bluray", "dvd", "edl");
    private static final Pattern FORCE = Pattern.compile("p[2-9]p");
    private BaseProtocolPolicy() {}

    public static String unsupportedReason(String url) {
        if (url == null) return null;
        String value = url.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        String scheme = colon > 0 ? value.substring(0, colon) : "";
        if (REMOVED.contains(scheme) || FORCE.matcher(scheme).matches()) {
            return "基础版不支持此协议（" + scheme + "）；请使用授权的普通媒体地址。";
        }
        String path = value.split("[?#;]", 2)[0];
        if (path.endsWith(".torrent") || path.endsWith(".iso")) {
            return "基础版不支持种子或光盘镜像播放。";
        }
        return null;
    }
}
