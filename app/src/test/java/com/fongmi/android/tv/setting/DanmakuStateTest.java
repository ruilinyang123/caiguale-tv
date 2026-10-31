package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuStateTest {

    @Test
    public void enabledRequiresLoadAndShow() {
        assertTrue(DanmakuState.isEnabled(true, true));
        assertFalse(DanmakuState.isEnabled(true, false));
        assertFalse(DanmakuState.isEnabled(false, true));
        assertFalse(DanmakuState.isEnabled(false, false));
    }

    @Test
    public void masterSwitchSynchronizesBothPreferences() {
        DanmakuState.Switches on = DanmakuState.setMaster(true);
        DanmakuState.Switches off = DanmakuState.setMaster(false);
        assertTrue(on.load());
        assertTrue(on.show());
        assertFalse(off.load());
        assertFalse(off.show());
    }
}
