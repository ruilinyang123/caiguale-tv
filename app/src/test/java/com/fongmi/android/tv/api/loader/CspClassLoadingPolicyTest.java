package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CspClassLoadingPolicyTest {

    @Test
    public void protobufClassesAreChildFirstOnlyWhenJarBundlesProtobuf() {
        assertTrue(CspClassLoadingPolicy.isProtobuf("com.google.protobuf.Internal"));
        assertTrue(CspClassLoadingPolicy.isChildFirst("com.google.protobuf.Internal", true));
        assertFalse(CspClassLoadingPolicy.isChildFirst("com.google.protobuf.Internal", false));
    }

    @Test
    public void platformAndHostClassesRemainParentFirst() {
        assertFalse(CspClassLoadingPolicy.isChildFirst("java.lang.String", true));
        assertFalse(CspClassLoadingPolicy.isChildFirst("android.content.Context", true));
        assertFalse(CspClassLoadingPolicy.isChildFirst("com.fongmi.android.tv.App", true));
        assertFalse(CspClassLoadingPolicy.isChildFirst("com.github.catvod.crawler.Spider", true));
        assertFalse(CspClassLoadingPolicy.isChildFirst(null, true));
    }

    @Test
    public void jarProtobufDetectionRequiresBundledClass() throws Exception {
        File withProtobuf = zip("com/google/protobuf/Internal.class");
        File withoutProtobuf = zip("com/example/Spider.class");
        assertTrue(JarLoader.containsProtobuf(withProtobuf));
        assertFalse(JarLoader.containsProtobuf(withoutProtobuf));
    }

    private File zip(String entry) throws Exception {
        File file = File.createTempFile("csp-policy", ".jar");
        file.deleteOnExit();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.putNextEntry(new ZipEntry(entry));
            output.write(0);
            output.closeEntry();
        }
        return file;
    }
}
