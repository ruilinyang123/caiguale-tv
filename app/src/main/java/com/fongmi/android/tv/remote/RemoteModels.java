package com.fongmi.android.tv.remote;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class RemoteModels {
    private RemoteModels() {}

    public static final class Profile {
        public boolean enabled;
        public String serverUrl = "";
        public String deviceId = "";
        public String deviceToken = "";
        public String name = "";
        public List<String> groupIds = new ArrayList<>();
        public List<Group> groups = new ArrayList<>();
        /** Monotonic local configuration generation; socket callbacks must match it. */
        public long revision;

        public Profile copy() {
            Profile copy = new Profile();
            copy.enabled = enabled;
            copy.serverUrl = serverUrl;
            copy.deviceId = deviceId;
            copy.deviceToken = deviceToken;
            copy.name = name;
            copy.revision = revision;
            copy.groupIds = groupIds == null ? new ArrayList<>() : new ArrayList<>(groupIds);
            copy.groups = new ArrayList<>();
            if (groups != null) for (Group group : groups) {
                Group item = new Group(); item.groupId = group.groupId; item.groupToken = group.groupToken; copy.groups.add(item);
            }
            return copy;
        }
    }

    public static final class Group {
        public String groupId = "";
        public String groupToken = "";
    }

    public static final class Command {
        public String id = "";
        public String groupId = "";
        public String targetDeviceId = "";
        public String type = "";
        public JsonObject payload = new JsonObject();
        public String status = "";
        public long createdAt;
        public long expiresAt;
        public JsonObject result;
    }

    public static final class RegisterResponse {
        public boolean ok;
        public String deviceId = "";
        public String deviceToken = "";
        public List<String> groupIds = new ArrayList<>();
        public JsonObject server;
    }
    public static final class BindCodeResponse {
        public boolean ok;
        public String code = "";
        public int expiresIn;
        public long expiresAt;
    }
    public static final class ClaimResponse {
        public boolean ok;
        public String groupId = "";
        public String groupToken = "";
        public String deviceId = "";
    }
    public static final class PollResponse {
        public boolean ok;
        public Command command;
        public List<String> groupIds = new ArrayList<>();
    }
    public static final class CommandResponse {
        public boolean ok;
        public String commandId = "";
        public Command command;
    }
    public static final class DevicesResponse {
        public boolean ok;
        public List<RemoteDevice> devices = new ArrayList<>();
    }
    public static final class RemoteDevice {
        public String deviceId = "";
        public String name = "";
        public String appVersion = "";
        public List<String> groupIds = new ArrayList<>();
        public long lastSeen;
        public boolean online;
    }
}
