package com.fongmi.android.tv.utils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Github {

    private static final String SERVER = "https://pan.imotao.com/file";
    private static final String GITHUB = "https://github.com/motao123/webtv/releases/latest/download";
    private static final String CNB = "https://cnb.cool/code_free/webtv/-/git/raw/main";
    private static final String GEO_URL = "https://ip-api.com/json?fields=countryCode";
    private static final String PREF_MIRROR = "update_mirror";

    private static volatile String baseUrl;

    public static String getUrl() {
        if (baseUrl == null) baseUrl = resolveBase();
        return baseUrl;
    }

    public static void setMirror(String mirror) {
        Prefers.put(PREF_MIRROR, mirror);
        if ("server".equals(mirror)) baseUrl = SERVER;
        else if ("github".equals(mirror)) baseUrl = GITHUB;
        else if ("cnb".equals(mirror)) baseUrl = CNB;
        else baseUrl = null;
    }

    public static String getMirror() {
        return Prefers.getString(PREF_MIRROR, "auto");
    }

    private static String resolveBase() {
        String mirror = getMirror();
        if ("server".equals(mirror)) return SERVER;
        if ("github".equals(mirror)) return GITHUB;
        if ("cnb".equals(mirror)) return CNB;
        if (isChina()) return SERVER;
        return GITHUB;
    }

    private static boolean isChina() {
        String cached = Prefers.getString("geo_country");
        if (cached != null && !cached.isEmpty()) return "CN".equals(cached);
        if (isZhLocale()) {
            Prefers.put("geo_country", "CN");
            return true;
        }
        try {
            String json = OkHttp.string(GEO_URL);
            String code = new JSONObject(json).optString("countryCode", "");
            Prefers.put("geo_country", code);
            SpiderDebug.log("Github", "geo: " + code + " -> " + ("CN".equals(code) ? "cnb.cool" : "github"));
            return "CN".equals(code);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return false;
        }
    }

    private static boolean isZhLocale() {
        java.util.Locale locale = java.util.Locale.getDefault();
        return "CN".equalsIgnoreCase(locale.getCountry()) || "zh".equalsIgnoreCase(locale.getLanguage());
    }

    private static String getUrl(String name) {
        String base = getUrl();
        return base + (base.contains("/releases/") ? "/" : "/apk/") + name;
    }

    public static String getJson(String name) {
        return getUrl(name + ".json");
    }

    public static String getApk(String name) {
        return getUrl(name + ".apk");
    }

    public static String getGithubRelease(String file) {
        // GITHUB already ends with /releases/latest/download, which is the only
        // asset path GitHub serves: /releases/latest/<file> answers 404.
        return GITHUB + "/" + file;
    }

    public static String getServerApk(String name) {
        return SERVER + "/apk/" + name;
    }

    public static String getServerJson(String name) {
        return SERVER + "/apk/" + name + ".json";
    }

    public static List<String> getJsonCandidates(String name) {
        List<String> result = new ArrayList<>();
        result.add(getUrl(name + ".json"));
        result.add(SERVER + "/apk/" + name + ".json");
        result.add(GITHUB + "/" + name + ".json");
        result.add(CNB + "/apk/" + name + ".json");
        return result.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    public static String getBase() {
        return getUrl();
    }
}
