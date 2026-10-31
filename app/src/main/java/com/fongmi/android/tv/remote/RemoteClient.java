package com.fongmi.android.tv.remote;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.remote.RemoteModels.BindCodeResponse;
import com.fongmi.android.tv.remote.RemoteModels.ClaimResponse;
import com.fongmi.android.tv.remote.RemoteModels.Command;
import com.fongmi.android.tv.remote.RemoteModels.CommandResponse;
import com.fongmi.android.tv.remote.RemoteModels.DevicesResponse;
import com.fongmi.android.tv.remote.RemoteModels.PollResponse;
import com.fongmi.android.tv.remote.RemoteModels.RegisterResponse;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class RemoteClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long TIMEOUT_MS = 12000;
    private static final long MAX_RESPONSE_BYTES = 1024 * 1024;
    private final RemoteModels.Profile profile;

    public RemoteClient(RemoteModels.Profile profile) { this.profile = profile == null ? new RemoteModels.Profile() : profile.copy(); }

    public RegisterResponse register() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", RemoteStore.deviceName());
        body.addProperty("appVersion", RemoteStore.appVersion());
        if (RemoteStore.hasIdentity()) {
            body.addProperty("deviceId", profile.deviceId);
            body.addProperty("deviceToken", profile.deviceToken);
        }
        RegisterResponse response = post("/api/device/register", body, "", RegisterResponse.class);
        if (response != null && response.ok) RemoteStore.applyRegistration(response);
        return response;
    }

    public BindCodeResponse bindCode() throws IOException {
        requireIdentity();
        return post("/api/device/bind-code", identityBody(), "", BindCodeResponse.class);
    }

    public ClaimResponse claim(String code, String groupToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code == null ? "" : code.trim());
        return post("/api/groups/claim", body, groupToken, ClaimResponse.class);
    }

    public DevicesResponse devices(String groupToken) throws IOException {
        return get("/api/devices", groupToken, DevicesResponse.class);
    }

    public PollResponse poll() throws IOException {
        requireIdentity();
        PollResponse response = post("/api/device/poll", identityBody(), "", PollResponse.class);
        if (response != null && response.groupIds != null) RemoteStore.applyGroups(response.groupIds);
        return response;
    }

    public CommandResponse command(String targetDeviceId, String type, JsonObject payload, String groupToken, String idempotencyKey, int ttlSeconds) throws IOException {
        if (TextUtils.isEmpty(targetDeviceId) || TextUtils.isEmpty(groupToken)) throw new IOException("Missing target or group");
        JsonObject body = new JsonObject();
        body.addProperty("targetDeviceId", targetDeviceId);
        body.addProperty("type", type);
        body.add("payload", payload == null ? new JsonObject() : payload);
        body.addProperty("idempotencyKey", idempotencyKey);
        if (ttlSeconds > 0) body.addProperty("ttlSeconds", Math.min(ttlSeconds, 120));
        return post("/api/commands", body, groupToken, CommandResponse.class);
    }

    public void result(String commandId, boolean ok, JsonObject result) throws IOException {
        requireIdentity();
        if (TextUtils.isEmpty(commandId) || !commandId.matches("[A-Za-z0-9._~-]+")) throw new IOException("Invalid command id");
        JsonObject body = new JsonObject();
        body.addProperty("ok", ok);
        body.add("result", result == null ? new JsonObject() : result);
        postRaw("/api/commands/" + commandId + "/result", body, "");
    }

    public Command getCommand(String commandId, String groupToken) throws IOException {
        if (TextUtils.isEmpty(commandId) || !commandId.matches("[A-Za-z0-9._~-]+")) throw new IOException("Invalid command id");
        JsonObject response = get("/api/commands/" + commandId, groupToken, JsonObject.class);
        return response == null || !response.has("command") ? null : App.gson().fromJson(response.get("command"), Command.class);
    }

    public void revoke() throws IOException { requireIdentity(); postRaw("/api/device/revoke", new JsonObject(), ""); }
    public void revokeGroup(String groupId) throws IOException { requireIdentity(); if (!groupId.matches("[A-Za-z0-9._~-]+")) throw new IOException("Invalid group id"); postRaw("/api/device/groups/" + groupId + "/revoke", new JsonObject(), ""); }

    public WebSocket openWebSocket(WebSocketListener listener) throws IOException {
        requireIdentity();
        Request request = new Request.Builder().url(webSocketUrl()).header("Authorization", "Bearer " + profile.deviceToken).header("X-Device-Id", profile.deviceId).build();
        return OkHttp.client().newBuilder().followRedirects(false).followSslRedirects(false).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build().newWebSocket(request, listener);
    }

    public String hello() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "hello");
        message.addProperty("deviceId", profile.deviceId);
        message.addProperty("deviceToken", profile.deviceToken);
        return App.gson().toJson(message);
    }

    public String resultMessage(String commandId, boolean ok, JsonObject result) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "result");
        message.addProperty("commandId", commandId);
        message.addProperty("ok", ok);
        message.add("result", result == null ? new JsonObject() : result);
        return App.gson().toJson(message);
    }

    private <T> T get(String path, String groupToken, Class<T> type) throws IOException { return execute(base(path, groupToken).get().build(), type); }
    private <T> T post(String path, JsonObject body, String groupToken, Class<T> type) throws IOException { return execute(base(path, groupToken).post(jsonBody(body)).build(), type); }
    private void postRaw(String path, JsonObject body, String groupToken) throws IOException { execute(base(path, groupToken).post(jsonBody(body)).build(), JsonObject.class); }

    private <T> T execute(Request request, Class<T> type) throws IOException {
        try (Response response = OkHttp.client(TIMEOUT_MS).newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (body == null) return null;
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
            byte[] bytes;
            try (java.io.InputStream input = body.byteStream()) {
                bytes = RemotePolicy.readBounded(input, (int) MAX_RESPONSE_BYTES);
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (TextUtils.isEmpty(text)) return null;
            T value = App.gson().fromJson(text, type);
            if (value == null) throw new IOException("Invalid response");
            return value;
        }
    }

    private Request.Builder base(String path, String groupToken) throws IOException {
        validateOrigin();
        Request.Builder builder = new Request.Builder().url(profile.serverUrl + path);
        if (!TextUtils.isEmpty(profile.deviceId)) builder.header("X-Device-Id", profile.deviceId);
        if (!TextUtils.isEmpty(profile.deviceToken) && TextUtils.isEmpty(groupToken)) builder.header("Authorization", "Bearer " + profile.deviceToken);
        if (!TextUtils.isEmpty(groupToken)) builder.header("Authorization", "Bearer " + groupToken);
        return builder;
    }

    private RequestBody jsonBody(JsonObject body) { return RequestBody.create(App.gson().toJson(body), JSON); }
    private JsonObject identityBody() { JsonObject body = new JsonObject(); body.addProperty("deviceId", profile.deviceId); body.addProperty("deviceToken", profile.deviceToken); return body; }
    private void requireIdentity() throws IOException { if (!RemoteStore.hasIdentity()) throw new IOException("Device identity is not registered"); }
    private String webSocketUrl() throws IOException { validateOrigin(); return "wss://" + profile.serverUrl.substring(8) + "/api/device/ws"; }

    private void validateOrigin() throws IOException {
        try {
            URI uri = URI.create(profile.serverUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || TextUtils.isEmpty(uri.getHost()) || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) throw new IOException("HTTPS relay required");
        } catch (IllegalArgumentException e) { throw new IOException("Invalid relay origin"); }
    }
}
