package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WallpaperMigrationPolicyTest {

    @Test
    public void restoresOnlyLegacyPurpleBuiltInDefault() {
        assertTrue(WallpaperMigrationPolicy.shouldRestoreClassicGreen(false, Setting.WALL_DREAM_PURPLE, 0));
        assertFalse(WallpaperMigrationPolicy.shouldRestoreClassicGreen(true, Setting.WALL_DREAM_PURPLE, 0));
        assertFalse(WallpaperMigrationPolicy.shouldRestoreClassicGreen(false, Setting.WALL_GREEN, 0));
        assertFalse(WallpaperMigrationPolicy.shouldRestoreClassicGreen(false, Setting.WALL_EMERALD_AURORA, 0));
        assertFalse(WallpaperMigrationPolicy.shouldRestoreClassicGreen(false, Setting.WALL_DREAM_PURPLE, 1));
        assertFalse(WallpaperMigrationPolicy.shouldRestoreClassicGreen(false, Setting.WALL_DREAM_PURPLE, 2));
    }
}
