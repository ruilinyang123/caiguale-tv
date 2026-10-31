package com.fongmi.android.tv.bean;

import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.ServerAuth;
import com.fongmi.android.tv.server.process.M3u8;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public record CastVideo(String name, String url, long position, Map<String, String> headers) {

    public CastVideo {
        // /file is a token-required path, so a locally stored video must carry the token too —
        // otherwise the renderer gets a 401 while the in-app player (which reads the file
        // directly) works fine.
        if (url.startsWith("file")) url = ServerAuth.withToken(Server.get().getAddress() + "/" + url.replace(Path.rootPath(), "").replace("://", ""));
        if (url.contains("127.0.0.1")) url = url.replace("127.0.0.1", Util.getIp());
        url = relay(url, headers);
    }

    /**
     * A renderer only ever receives a URL, so it cannot attach the Referer / User-Agent / Cookie
     * that most HLS sources check on every segment — which is why such a source plays fine in the
     * app but fails on a TV. When the player needed headers we relay through this device instead:
     * it fetches with the real headers and hands the rewritten playlist to the TV.
     *
     * <p>Links that need no headers are still cast directly, so they pay no relay cost and the
     * phone stays out of the stream path.</p>
     */
    private static String relay(String url, Map<String, String> headers) {
        // Already routed through us: Python live sources emit 127.0.0.1/m3u8 links, which the step
        // above rewrote to the LAN address. The renderer now arrives over the LAN, so the URL has
        // to carry the server token or the fetch is rejected.
        if (url.contains("/m3u8?")) return url.contains("token=") ? url : ServerAuth.withToken(url);
        if (!isPlaylist(url) || headers == null || headers.isEmpty()) return url;
        String id = M3u8.registerHeaders(headers);
        if (id.isEmpty()) return url;
        String path = "/m3u8?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&" + M3u8.PARAM_ID + "=" + id;
        return ServerAuth.withToken(Server.get().getAddress(false) + path);
    }

    /**
     * Kept deliberately loose: a missed playlist means a cast that silently plays nothing, while
     * a false positive merely adds one local hop. Plenty of sources hide the format behind a
     * query, so "m3u8 mentioned anywhere" is the safer trade than a strict suffix test.
     */
    private static boolean isPlaylist(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains(".m3u") || lower.contains("m3u8");
    }
}