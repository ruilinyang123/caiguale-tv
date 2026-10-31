package com.fongmi.android.tv.web;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;

/**
 * 菜瓜乐影视 WebHome 数据桥：把原生 TVBox 站源能力（片单/分类/详情/搜索/起播）
 * 以 JSON 暴露给本地首页，网页不再自己实现一套采集逻辑。
 * 方法均在 HomeWebBridge 的后台线程被调用，禁止碰 UI（起播用 App.post）。
 */
public final class CaigualeVodBridge {

    private static final int SEARCH_SITE_LIMIT = 6;

    private CaigualeVodBridge() {
    }

    /** 可用内容站点列表（排除首页自身这类无内容能力的站点）。 */
    public static String sites() {
        JsonArray array = new JsonArray();
        for (Site site : VodConfig.get().getSites()) {
            if (site == null || site.isEmpty()) continue;
            if (!TextUtils.isEmpty(site.getHomePage())) continue;
            JsonObject item = new JsonObject();
            item.addProperty("key", site.getKey());
            item.addProperty("name", site.getName());
            item.addProperty("type", site.getType());
            array.add(item);
        }
        return array.toString();
    }

    public static String home(String siteKey) throws Exception {
        Site site = site(siteKey);
        Result result = SiteApi.homeContent(site);
        JsonObject object = new JsonObject();
        JsonArray classes = new JsonArray();
        for (Class type : result.getTypes()) {
            JsonObject item = new JsonObject();
            item.addProperty("typeId", type.getTypeId());
            item.addProperty("typeName", type.getTypeName());
            classes.add(item);
        }
        object.add("class", classes);
        object.add("list", list(result.getList()));
        object.addProperty("pageCount", result.getPageCount());
        object.addProperty("siteKey", site.getKey());
        object.addProperty("siteName", site.getName());
        return object.toString();
    }

    public static String category(String siteKey, String tid, String page) throws Exception {
        Site site = site(siteKey);
        String index = TextUtils.isEmpty(page) ? "1" : page;
        Result result = SiteApi.categoryContent(site.getKey(), tid, index, false, new HashMap<>());
        JsonObject object = new JsonObject();
        object.addProperty("page", index);
        object.addProperty("pageCount", result.getPageCount());
        object.add("list", list(result.getList()));
        return object.toString();
    }

    public static String detail(String siteKey, String vodId) throws Exception {
        Site site = site(siteKey);
        Result result = SiteApi.detailContent(site.getKey(), vodId);
        List<Vod> items = result.getList();
        if (items.isEmpty()) throw new IllegalStateException("详情为空");
        Vod vod = items.get(0);
        JsonObject object = vod(vod);
        object.addProperty("siteKey", site.getKey());
        JsonArray flags = new JsonArray();
        for (Flag flag : vod.getFlags()) {
            JsonObject node = new JsonObject();
            node.addProperty("flag", flag.getFlag());
            JsonArray episodes = new JsonArray();
            for (Episode episode : flag.getEpisodes()) {
                JsonObject ep = new JsonObject();
                ep.addProperty("name", episode.getName());
                ep.addProperty("url", episode.getUrl());
                episodes.add(ep);
            }
            node.add("episodes", episodes);
            flags.add(node);
        }
        object.add("flags", flags);
        return object.toString();
    }

    public static String search(String keyword, String siteKey, String page) {
        String index = TextUtils.isEmpty(page) ? "1" : page;
        JsonArray array = new JsonArray();
        if (!TextUtils.isEmpty(siteKey)) {
            collect(site(siteKey), keyword, index, array);
            return array.toString();
        }
        int tried = 0;
        for (Site site : VodConfig.get().getSites()) {
            if (site == null || site.isEmpty() || !TextUtils.isEmpty(site.getHomePage())) continue;
            if (tried++ >= SEARCH_SITE_LIMIT) break;
            try {
                collect(site, keyword, index, array);
            } catch (Throwable ignored) {
                // 单站失败不影响聚合搜索
            }
        }
        return array.toString();
    }

    public static String play(Activity activity, String siteKey, String vodId, String title, String pic, String mark, String flag) {
        final String key = siteKey;
        final String id = vodId;
        final String name = TextUtils.isEmpty(title) ? vodId : title;
        final String image = pic;
        final String episode = mark;
        final String line = flag;
        App.post(() -> VideoActivity.start(activity, key, id, name, image, episode, line, false, false));
        JsonObject object = new JsonObject();
        object.addProperty("started", true);
        return object.toString();
    }

    private static void collect(Site site, String keyword, String page, JsonArray array) {
        Result result;
        try {
            result = SiteApi.searchContent(site, keyword, false, page);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        for (Vod item : result.getList()) {
            JsonObject object = vod(item);
            object.addProperty("siteKey", site.getKey());
            object.addProperty("siteName", site.getName());
            array.add(object);
        }
    }

    private static JsonArray list(List<Vod> items) {
        JsonArray array = new JsonArray();
        for (Vod item : items) array.add(vod(item));
        return array;
    }

    private static JsonObject vod(Vod item) {
        JsonObject object = new JsonObject();
        object.addProperty("id", item.getId());
        object.addProperty("name", item.getName());
        object.addProperty("pic", item.getPic());
        object.addProperty("remarks", item.getRemarks());
        object.addProperty("year", item.getYear());
        object.addProperty("area", item.getArea());
        object.addProperty("typeName", item.getTypeName());
        object.addProperty("content", item.getContent());
        return object;
    }

    private static Site site(String key) {
        Site site = VodConfig.get().getSite(key);
        if (site == null || site.isEmpty()) throw new IllegalArgumentException("站点不存在: " + key);
        return site;
    }
}
