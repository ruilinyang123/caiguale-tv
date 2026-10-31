package com.fongmi.android.tv.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DanmakuSearchGenerationTest {

    @Test
    public void onlyLatestGenerationForCurrentPlaybackIsAccepted() {
        DanmakuSearchGeneration generations = new DanmakuSearchGeneration();
        DanmakuSearchGeneration.Token first = generations.begin("site|show|ep1");
        assertTrue(generations.isCurrent(first, "site|show|ep1"));

        DanmakuSearchGeneration.Token second = generations.begin("site|show|ep2");
        assertFalse(generations.isCurrent(first, "site|show|ep1"));
        assertFalse(generations.isCurrent(second, "site|show|ep1"));
        assertTrue(generations.isCurrent(second, "site|show|ep2"));
    }

    @Test
    public void invalidationRejectsAlreadyPostedCallback() {
        DanmakuSearchGeneration generations = new DanmakuSearchGeneration();
        DanmakuSearchGeneration.Token token = generations.begin("playing");
        generations.invalidate();
        assertFalse(generations.isCurrent(token, "playing"));
    }
}
