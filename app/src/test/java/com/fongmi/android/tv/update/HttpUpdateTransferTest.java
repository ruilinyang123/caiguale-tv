package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class HttpUpdateTransferTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void parsesContentRangeStart() {
        assertEquals(0, HttpUpdateTransfer.parseRangeStart("bytes 0-99/1000"));
        assertEquals(100, HttpUpdateTransfer.parseRangeStart("bytes 100-199/1000"));
        assertEquals(100, HttpUpdateTransfer.parseRangeStart("bytes 100-/*"));
        assertEquals(1048576, HttpUpdateTransfer.parseRangeStart("bytes 1048576-2097151/5242880"));
    }

    @Test
    public void rejectsUnparsableContentRange() {
        assertEquals(-1, HttpUpdateTransfer.parseRangeStart(null));
        assertEquals(-1, HttpUpdateTransfer.parseRangeStart(""));
        assertEquals(-1, HttpUpdateTransfer.parseRangeStart("bytes */1000"));
        assertEquals(-1, HttpUpdateTransfer.parseRangeStart("items 0-99/1000"));
        assertEquals(-1, HttpUpdateTransfer.parseRangeStart("bytes-"));
        assertEquals(-1, HttpUpdateTransfer.parseRangeStart("bytes abc-199/1000"));
    }

    @Test
    public void resumesOnlyValidPartials() throws Exception {
        File partial = folder.newFile("update.apk");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(new byte[500]);
        }
        // A healthy partial shorter than the manifest size resumes at its length.
        assertEquals(500, new HttpUpdateTransfer("https://example.com/update.apk", partial, 1000).resumableOffset());
        // No size in the manifest: a stale file is indistinguishable, never resume.
        assertEquals(0, new HttpUpdateTransfer("https://example.com/update.apk", partial, 0).resumableOffset());
        // A partial longer than the expected APK is stale, restart from zero.
        assertEquals(0, new HttpUpdateTransfer("https://example.com/update.apk", partial, 100).resumableOffset());
    }

    @Test
    public void keepsCompleteLeftoverForValidation() throws Exception {
        File partial = folder.newFile("update.apk");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(new byte[500]);
        }
        // A leftover file matching the manifest size is handed to validation
        // (checksum/package identity) instead of being re-downloaded.
        assertEquals(500, new HttpUpdateTransfer("https://example.com/update.apk", partial, 500).resumableOffset());
    }

    @Test
    public void ignoresMissingPartial() {
        File missing = new File(folder.getRoot(), "absent.apk");
        assertEquals(0, new HttpUpdateTransfer("https://example.com/update.apk", missing, 1000).resumableOffset());
    }
}
