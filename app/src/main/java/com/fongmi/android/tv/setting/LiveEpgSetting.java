package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LiveEpgSetting {

    private static final String KEY_URL = "live_epg_url";
    private static final String KEY_HISTORY = "live_epg_history";
    private static final int MAX_HISTORY = 20;
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    public static String getUrl() {
        return Prefers.getString(KEY_URL, "");
    }

    public static void putUrl(String url) {
        url = normalize(url);
        Prefers.put(KEY_URL, url);
        addHistory(url);
    }

    public static List<String> getHistory() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(KEY_HISTORY, "[]"), TYPE);
            return items == null ? new ArrayList<>() : new ArrayList<>(items);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void addHistory(String url) {
        url = normalize(url);
        if (url.isEmpty()) return;
        List<String> items = getHistory();
        items.remove(url);
        items.add(0, url);
        while (items.size() > MAX_HISTORY) items.remove(items.size() - 1);
        saveHistory(items);
    }

    public static void removeHistory(String url) {
        url = normalize(url);
        if (url.isEmpty()) return;
        List<String> items = getHistory();
        if (items.remove(url)) saveHistory(items);
        if (getUrl().equals(url)) Prefers.put(KEY_URL, "");
    }

    public static void removeHistory(List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        List<String> items = getHistory();
        items.removeAll(urls);
        saveHistory(items);
        if (urls.contains(getUrl())) Prefers.put(KEY_URL, "");
    }

    public static void clearHistory() {
        saveHistory(Collections.emptyList());
    }

    public static void apply(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        for (Group group : live.getGroups()) for (Channel channel : group.getChannel()) apply(live, channel);
    }

    public static void apply(Live live, Channel channel) {
        if (live == null || channel == null) return;
        channel.setDataList(Collections.emptyList());
        String template = getEffectiveUrl(live);
        if (template.isEmpty() || isGlobalXmlUrl(template)) {
            if (isGlobalXmlUrl(template)) channel.setEpg("");
            return;
        }
        if (!template.contains("{")) {
            channel.setEpg(template);
            return;
        }
        channel.setEpg(template
                .replace("{id}", encode(channel.getTvgId()))
                .replace("{name}", encode(channel.getTvgName()))
                .replace("{epg}", encode(channel.getEpg())));
    }

    public static String getEffectiveUrl(Live live) {
        String custom = getUrl();
        return custom.isEmpty() && live != null ? live.getEpgApi() : custom;
    }

    public static List<String> getXmlUrls(Live live) {
        List<String> custom = getCustomXmlUrls();
        if (!custom.isEmpty()) return custom;
        Set<String> items = new LinkedHashSet<>();
        if (live != null) for (String url : live.getEpgXml()) if (isXmlUrl(url)) items.add(normalize(url));
        return new ArrayList<>(items);
    }

    public static boolean hasCustomXml() {
        return !getCustomXmlUrls().isEmpty();
    }

    public static boolean isGlobalXmlUrl(String url) {
        List<String> parts = split(url);
        return !parts.isEmpty() && parts.stream().allMatch(LiveEpgSetting::isXmlUrl);
    }

    static boolean isXmlUrl(String url) {
        url = normalize(url);
        if (url.isEmpty() || url.contains("{")) return false;
        try {
            String path = URI.create(url).getPath();
            if (path == null) return false;
            path = path.toLowerCase(Locale.ROOT);
            return path.endsWith(".xml") || path.endsWith(".xml.gz") || path.endsWith(".gz");
        } catch (Exception e) {
            return false;
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<String> getCustomXmlUrls() {
        Set<String> items = new LinkedHashSet<>();
        for (String url : split(getUrl())) if (isXmlUrl(url)) items.add(url);
        return new ArrayList<>(items);
    }

    private static List<String> split(String value) {
        List<String> items = new ArrayList<>();
        for (String part : normalize(value).split(",")) {
            part = normalize(part);
            if (!part.isEmpty()) items.add(part);
        }
        return items;
    }

    private static void saveHistory(List<String> items) {
        Prefers.put(KEY_HISTORY, App.gson().toJson(items));
    }

    private static String normalize(String url) {
        return url == null ? "" : url.trim();
    }
}
