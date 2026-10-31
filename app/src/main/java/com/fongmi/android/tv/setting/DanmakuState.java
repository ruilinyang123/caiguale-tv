package com.fongmi.android.tv.setting;

/** Pure policy for the persisted danmaku switches. */
public final class DanmakuState {

    private DanmakuState() {
    }

    public static boolean isEnabled(boolean load, boolean show) {
        return load && show;
    }

    public static Switches setMaster(boolean enabled) {
        return new Switches(enabled, enabled);
    }

    public record Switches(boolean load, boolean show) {
    }
}
