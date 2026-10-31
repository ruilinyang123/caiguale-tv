package com.fongmi.android.tv.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Android-free trust boundary and transport policy. Times for commands are epoch milliseconds. */
public final class RemotePolicy {
    private static final Set<String> CONTROL = Set.of("play", "pause", "stop", "prev", "next", "repeat", "loop", "replay");
    private RemotePolicy() {}

    public static String origin(String value) {
        String text = value == null ? "" : value.trim();
        try {
            URI uri = URI.create(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return "https://" + uri.getRawAuthority().toLowerCase(java.util.Locale.ROOT) + ("" );
        } catch (RuntimeException e) { throw new IllegalArgumentException("HTTPS relay origin required"); }
    }

    public static boolean hasIdentity(RemoteModels.Profile p) { return p != null && !empty(p.deviceId) && !empty(p.deviceToken); }
    public static boolean sameIdentity(RemoteModels.Profile a, RemoteModels.Profile b) {
        return a != null && b != null && a.enabled == b.enabled && Objects.equals(a.serverUrl, b.serverUrl)
                && Objects.equals(a.deviceId, b.deviceId) && Objects.equals(a.deviceToken, b.deviceToken);
    }

    public static boolean valid(RemoteModels.Command c, RemoteModels.Profile p, long now) {
        return c != null && p != null && p.enabled && hasIdentity(p) && !empty(c.id) && c.id.length() <= 128
                && c.id.matches("[A-Za-z0-9._~-]+") && p.deviceId.equals(c.targetDeviceId)
                && !empty(c.groupId) && p.groupIds != null && p.groupIds.contains(c.groupId)
                && c.createdAt > 0 && c.createdAt <= now && c.expiresAt > now
                && c.expiresAt > c.createdAt && c.expiresAt - c.createdAt <= 120_000
                && validPayload(c.type, c.payload);
    }

    public static boolean validPayload(String type, JsonObject payload) {
        if (payload == null || type == null) return false;
        if ("device.status".equals(type)) return payload.size() == 0;
        String key = switch (type) { case "action.search" -> "word"; case "action.push" -> "url"; case "action.control" -> "action"; default -> ""; };
        if (key.isEmpty() || payload.size() != 1) return false;
        JsonElement item = payload.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return false;
        String value = item.getAsString();
        if ("word".equals(key)) return !value.trim().isEmpty() && value.codePointCount(0, value.length()) <= 200;
        if ("action".equals(key)) return CONTROL.contains(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > 4096) return false;
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawFragment() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535;
        } catch (RuntimeException e) { return false; }
    }

    public static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1))) != -1) {
            if (count > limit - output.size()) throw new IOException("Response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean empty(String text) { return text == null || text.isEmpty(); }

    /** Bounded by the relay rate over the maximum 120s TTL, never evict a live command ID. */
    public static final class Dedup {
        private final Map<String, Long> expiry = new HashMap<>();
        public boolean claim(String id, long expiresAt, long now) {
            expiry.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (expiresAt <= now || expiry.containsKey(id)) return false;
            expiry.put(id, expiresAt);
            return true;
        }
    }

    /** All calls are serialized by the agent monitor. Retry times use a monotonic clock. */
    public static final class Connection {
        public boolean connecting;
        public boolean ready;
        private long startedAt;
        private long retryAt;
        private int failures;
        public boolean canConnect(long now) { return !connecting && !ready && now >= retryAt; }
        public void connecting(long now) { connecting = true; startedAt = now; }
        public boolean timedOut(long now) { return connecting && now - startedAt >= 12_000; }
        public void ready() { connecting = false; ready = true; }
        public void failed(long now) {
            connecting = ready = false;
            // Keep backoff across brief ready/disconnect loops; reset only with a new agent session.
            retryAt = now + Math.min(60_000L, 4_000L << Math.min(failures++, 4));
        }
        public boolean shouldPoll() { return !ready; }
        public void reset() { connecting = ready = false; startedAt = retryAt = 0L; failures = 0; }
    }
}
