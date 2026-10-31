package com.fongmi.android.tv.setting;

public final class WallpaperMigrationPolicy {

    private WallpaperMigrationPolicy() {}

    public static boolean shouldRestoreClassicGreen(boolean migrated, int wall, int wallType) {
        return !migrated && wallType == 0 && wall == Setting.WALL_DREAM_PURPLE;
    }
}
