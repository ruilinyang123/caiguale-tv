package com.fongmi.android.tv.remote;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemotePolicyTest {
    @Test public void acceptsOnlyCurrentAuthorizedUnexpiredCommand() {
        long now = 1_000_000L;
        RemoteModels.Profile profile = profile();
        RemoteModels.Command command = command(now);
        assertTrue(RemotePolicy.valid(command, profile, now));
        command.targetDeviceId = "other";
        assertFalse(RemotePolicy.valid(command, profile, now));
        command.targetDeviceId = profile.deviceId;
        command.groupId = "revoked";
        assertFalse(RemotePolicy.valid(command, profile, now));
        command.groupId = "family";
        command.expiresAt = now;
        assertFalse(RemotePolicy.valid(command, profile, now));
    }

    @Test public void payloadIsExactAndTypeSpecific() {
        JsonObject search = new JsonObject();
        search.addProperty("word", "movie");
        assertTrue(RemotePolicy.validPayload("action.search", search));
        search.addProperty("extra", true);
        assertFalse(RemotePolicy.validPayload("action.search", search));

        JsonObject push = new JsonObject();
        push.addProperty("url", "https://example.com/video.mp4");
        assertTrue(RemotePolicy.validPayload("action.push", push));
        push.addProperty("fragment", "nope");
        assertFalse(RemotePolicy.validPayload("action.push", push));

        JsonObject status = new JsonObject();
        assertTrue(RemotePolicy.validPayload("device.status", status));
    }

    @Test public void dedupKeepsLiveIdsAndRemovesExpiredIds() {
        RemotePolicy.Dedup dedup = new RemotePolicy.Dedup();
        assertTrue(dedup.claim("one", 2_000, 1_000));
        assertFalse(dedup.claim("one", 3_000, 1_001));
        assertTrue(dedup.claim("one", 3_000, 2_000));
    }

    @Test public void failedConnectionBacksOffButStillAllowsPolling() {
        RemotePolicy.Connection connection = new RemotePolicy.Connection();
        assertTrue(connection.canConnect(0));
        connection.connecting(0);
        assertTrue(connection.timedOut(12_000));
        connection.failed(12_000);
        assertTrue(connection.shouldPoll());
        assertFalse(connection.canConnect(12_001));
        assertTrue(connection.canConnect(16_000));
        connection.ready();
        assertFalse(connection.shouldPoll());
    }

    @Test public void rejectsNonHttpsRelayOrigins() {
        assertTrue("https://example.com".equals(RemotePolicy.origin("https://example.com/")));
        try {
            RemotePolicy.origin("http://example.com");
            throw new AssertionError("http origin accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static RemoteModels.Profile profile() {
        RemoteModels.Profile profile = new RemoteModels.Profile();
        profile.enabled = true;
        profile.deviceId = "device";
        profile.deviceToken = "token";
        profile.groupIds.add("family");
        return profile;
    }

    private static RemoteModels.Command command(long now) {
        RemoteModels.Command command = new RemoteModels.Command();
        command.id = "command-1";
        command.groupId = "family";
        command.targetDeviceId = "device";
        command.type = "action.control";
        command.createdAt = now - 100;
        command.expiresAt = now + 1_000;
        command.payload.addProperty("action", "pause");
        return command;
    }
}
