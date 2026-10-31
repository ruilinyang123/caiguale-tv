package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        Spider spider = createSpider(key, api, ext, jar);
        // Same rule as JarLoader: an unavailable spider is usually a transient state
        // (the jar's remote-dependency confirmation had no Activity to show in), so
        // never memoize the null — a later request must be able to ask again.
        if (spider instanceof SpiderNull) return spider;
        Spider existing = spiders.putIfAbsent(key, spider);
        return existing != null ? existing : spider;
    }

    private Spider createSpider(String key, String api, String ext, String jar) {
        try {
            Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
            spider.siteKey = key;
            spider.init(App.get(), ext);
            return spider;
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return new SpiderNull();
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
