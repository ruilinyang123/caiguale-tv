package com.fongmi.android.tv.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlaybackRemoteSyncStoreTest {

    @Test
    public void normalizeFillsDefaultsAndBoundsValues() {
        RemoteSyncConfig config = new RemoteSyncConfig();
        config.id = "";
        config.name = null;
        config.url = null;
        config.token = null;
        config.siteKeys = null;
        config.cursors = null;
        config.intervalMinutes = -1;
        config.maxItems = 2001;
        config.lastError = null;

        RemoteSyncConfig normalized = PlaybackRemoteSyncStore.normalize(config);

        assertNotNull(normalized.id);
        assertTrue(!normalized.id.isEmpty());
        assertEquals("", normalized.name);
        assertEquals("", normalized.url);
        assertEquals("", normalized.token);
        assertNotNull(normalized.siteKeys);
        assertNotNull(normalized.cursors);
        assertEquals(0, normalized.intervalMinutes);
        assertEquals(1000, normalized.maxItems);
        assertEquals("", normalized.lastError);
    }
}
