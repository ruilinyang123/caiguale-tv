package com.fongmi.android.tv.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuSearchRequestOwnerTest {

    @Test
    public void onlyLatestManualRequestIsCurrent() {
        DanmakuSearchRequestOwner owner = new DanmakuSearchRequestOwner();
        DanmakuSearchRequestOwner.Token first = owner.begin();
        DanmakuSearchRequestOwner.Token second = owner.begin();

        assertFalse(owner.isCurrent(first));
        assertTrue(owner.isCurrent(second));
    }

    @Test
    public void destroyingOwnerRejectsAlreadyPostedCallback() {
        DanmakuSearchRequestOwner owner = new DanmakuSearchRequestOwner();
        DanmakuSearchRequestOwner.Token token = owner.begin();

        owner.invalidate();

        assertFalse(owner.isCurrent(token));
    }

    @Test
    public void eachManualDialogHasIndependentRequestTag() {
        DanmakuSearchRequestOwner first = new DanmakuSearchRequestOwner();
        DanmakuSearchRequestOwner second = new DanmakuSearchRequestOwner();

        assertNotEquals(first.tag(), second.tag());
    }
}
