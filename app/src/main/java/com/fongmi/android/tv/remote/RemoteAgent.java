package com.fongmi.android.tv.remote;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.JsonObject;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Opt-in relay agent. WS is preferred; HTTP polling remains live while WS backs off. */
public final class RemoteAgent {
    private static final long POLL_MS = 2000L;
    private static final long WS_CONNECT_TIMEOUT_MS = 12000L;
    private static volatile RemoteAgent instance;
    private final RemotePolicy.Connection connection = new RemotePolicy.Connection();
    private final RemotePolicy.Dedup dedup = new RemotePolicy.Dedup();
    private ScheduledFuture<?> poll;
    private WebSocket socket;
    private long generation;

    private RemoteAgent() {}
    public static RemoteAgent get() { if (instance == null) synchronized (RemoteAgent.class) { if (instance == null) instance = new RemoteAgent(); } return instance; }

    public synchronized void start() {
        RemoteModels.Profile profile = RemoteStore.snapshot();
        if (!profile.enabled || TextUtils.isEmpty(profile.serverUrl)) return;
        if (poll != null && !poll.isCancelled()) return;
        generation++;
        connection.reset();
        poll = Task.scheduler().scheduleWithFixedDelay(this::tick, 0, POLL_MS, TimeUnit.MILLISECONDS);
        try { RemoteAgentService.start(App.get()); } catch (Throwable ignored) {}
    }

    public synchronized void stop() {
        generation++;
        if (poll != null) poll.cancel(false);
        poll = null;
        connection.reset();
        closeSocketLocked();
        try { RemoteAgentService.stop(App.get()); } catch (Throwable ignored) {}
    }

    public synchronized boolean isRunning() { return poll != null && !poll.isCancelled(); }

    private void tick() {
        RemoteModels.Profile profile;
        long session;
        synchronized (this) {
            if (!isRunning()) return;
            profile = RemoteStore.snapshot();
            session = generation;
            if (!profile.enabled || TextUtils.isEmpty(profile.serverUrl)) { stop(); return; }
            if (socket != null && !RemotePolicy.sameIdentity(profile, activeProfile)) closeSocketLocked();
            if (connection.timedOut(System.currentTimeMillis())) {
                closeSocketLocked();
                connection.failed(System.currentTimeMillis());
            }
        }
        try {
            RemoteClient client = new RemoteClient(profile);
            if (!RemoteStore.hasIdentity()) { client.register(); return; }
            synchronized (this) {
                if (!isCurrent(session)) return;
                if (socket == null && connection.canConnect(System.currentTimeMillis())) {
                    connection.connecting(System.currentTimeMillis());
                    openSocket(client, profile, session);
                    return;
                }
                if (connection.ready && socket != null) return;
            }
            // No ready socket: keep polling even during WS exponential backoff.
            RemoteModels.PollResponse response = client.poll();
            if (response != null && response.groupIds != null) RemoteStore.applyGroups(response.groupIds);
            if (response != null && response.command != null) dispatch(client, profile, response.command, session);
        } catch (Throwable ignored) {
            synchronized (this) { if (isCurrent(session) && !connection.ready) connection.failed(System.currentTimeMillis()); }
        }
    }

    private RemoteModels.Profile activeProfile;
    private void openSocket(RemoteClient client, RemoteModels.Profile profile, long session) {
        try {
            activeProfile = profile.copy();
            WebSocket opened = client.openWebSocket(new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    synchronized (RemoteAgent.this) {
                        if (!isCurrent(session) || socket != null && socket != webSocket) { webSocket.close(1000, "stale"); return; }
                        socket = webSocket;
                    }
                    if (!webSocket.send(client.hello())) { failed(webSocket, session); }
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    if (!isCurrentSocket(webSocket, session)) return;
                    try {
                        JsonObject object = App.gson().fromJson(text, JsonObject.class);
                        String type = string(object, "type");
                        if ("ready".equals(type) && object.has("ok") && object.get("ok").getAsBoolean()) {
                            synchronized (RemoteAgent.this) {
                                if (!isCurrentSocket(webSocket, session)) return;
                                connection.ready();
                            }
                            if (object.has("groupIds")) RemoteStore.applyGroups(App.gson().fromJson(object.get("groupIds"), java.util.List.class));
                        } else if ("command".equals(type) && object.has("command")) {
                            dispatch(client, profile, App.gson().fromJson(object.get("command"), RemoteModels.Command.class), session);
                        }
                    } catch (Throwable ignored) {}
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) { failed(webSocket, session); }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) { failed(webSocket, session); }
            });
            synchronized (this) {
                if (!isCurrent(session) || !connection.connecting) { opened.close(1000, "stale"); return; }
                if (socket == null) socket = opened;
                else if (socket != opened) { opened.close(1000, "stale"); return; }
            }
        } catch (Throwable ignored) { synchronized (this) { if (isCurrent(session)) { connection.failed(System.currentTimeMillis()); socket = null; } } }
    }

    private void failed(WebSocket webSocket, long session) {
        synchronized (this) {
            if (!isCurrentSocket(webSocket, session)) return;
            socket = null;
            connection.failed(System.currentTimeMillis());
        }
        try { webSocket.close(1001, "reconnect"); } catch (Throwable ignored) {}
    }

    private void dispatch(RemoteClient client, RemoteModels.Profile profile, RemoteModels.Command command, long session) {
        long now = System.currentTimeMillis();
        if (!RemotePolicy.valid(command, profile, now)) return;
        synchronized (this) {
            if (!dedup.claim(command.id, command.expiresAt, now)) return;
        }
        Task.execute(() -> {
            synchronized (RemoteAgent.this) {
                RemoteModels.Profile latest = RemoteStore.snapshot();
                if (!isCurrent(session) || !RemotePolicy.valid(command, latest, System.currentTimeMillis())) return;
            }
            JsonObject result = RemoteCommandExecutor.execute(command);
            boolean ok = !result.has("error");
            try {
                WebSocket current;
                synchronized (RemoteAgent.this) { current = isCurrent(session) && connection.ready ? socket : null; }
                if (current != null && current.send(client.resultMessage(command.id, ok, result))) return;
                // WS may disappear after execution; HTTP result is the protocol fallback.
                if (isCurrent(session)) client.result(command.id, ok, result);
            } catch (Throwable ignored) {}
        });
    }

    private synchronized boolean isCurrent(long session) { return isRunning() && generation == session; }
    private synchronized boolean isCurrentSocket(WebSocket candidate, long session) { return isCurrent(session) && socket == candidate; }
    private synchronized void closeSocketLocked() {
        WebSocket old = socket;
        socket = null;
        activeProfile = null;
        if (old != null) try { old.close(1000, "stopped"); } catch (Throwable ignored) {}
    }
    private static String string(JsonObject object, String key) { return object != null && object.has(key) ? object.get(key).getAsString() : ""; }
}
