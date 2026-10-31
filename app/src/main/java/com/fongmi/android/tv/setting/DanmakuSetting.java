package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** Base build keeps danmaku source preferences only; the fork renderer is not shipped. */
public class DanmakuSetting {

    public static boolean isLoad() { return Prefers.getBoolean("danmaku_load"); }
    public static void putLoad(boolean load) { Prefers.put("danmaku_load", load); }
    public static boolean isShow() { return Prefers.getBoolean("danmaku_show", true); }
    public static void putShow(boolean show) { Prefers.put("danmaku_show", show); }
    public static boolean isEnabled() { return isLoad() && isShow(); }
    public static boolean isAuto() { return Prefers.getBoolean("danmaku_auto"); }
    public static void putAuto(boolean auto) { Prefers.put("danmaku_auto", auto); }
    public static boolean isSpiderFirst() { return Prefers.getBoolean("danmaku_spider_first"); }
    public static void putSpiderFirst(boolean first) { Prefers.put("danmaku_spider_first", first); }
    public static String getApiUrl() { return Prefers.getString("danmaku_api_url", ""); }
    public static void putApiUrl(String url) { Prefers.put("danmaku_api_url", url); }
    public static String getEffectiveApiUrl() { return getApiUrl(); }
    public static void putEnabled(boolean enabled) { putLoad(enabled); putShow(enabled); }
}
