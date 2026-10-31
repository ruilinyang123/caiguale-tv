package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalNetworkPermissionPolicyTest {

    @Test
    public void preAndroid17DoesNotRequireRuntimePermission() {
        assertTrue(LocalNetworkPermissionPolicy.isGranted(36, false));
    }

    @Test
    public void android17RequiresRuntimePermission() {
        assertFalse(LocalNetworkPermissionPolicy.isGranted(37, false));
        assertTrue(LocalNetworkPermissionPolicy.isGranted(37, true));
        assertFalse(LocalNetworkPermissionPolicy.isGranted(40, false));
    }

    @Test
    public void loopbackUrlsRemainAvailableWithoutPermission() {
        assertTrue(LocalNetworkPermissionPolicy.canAccess(37, false, "http://127.0.0.1:9978/device"));
        assertTrue(LocalNetworkPermissionPolicy.canAccess(37, false, "http://localhost:9978/device"));
        assertTrue(LocalNetworkPermissionPolicy.canAccess(37, false, "http://[::1]:9978/device"));
    }

    @Test
    public void lanUrlsRequirePermissionOnAndroid17() {
        assertFalse(LocalNetworkPermissionPolicy.canAccess(37, false, "http://192.168.1.2:9978/device"));
        assertTrue(LocalNetworkPermissionPolicy.canAccess(37, true, "http://192.168.1.2:9978/device"));
    }

    @Test
    public void malformedOrMissingUrlsAreNotTreatedAsLoopback() {
        assertFalse(LocalNetworkPermissionPolicy.isLoopback(null));
        assertFalse(LocalNetworkPermissionPolicy.isLoopback("not a url"));
    }
}
