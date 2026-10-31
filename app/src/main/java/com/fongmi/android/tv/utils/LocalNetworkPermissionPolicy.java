package com.fongmi.android.tv.utils;

import java.net.URI;

public final class LocalNetworkPermissionPolicy {

    public static final int ANDROID_17_API = 37;

    private LocalNetworkPermissionPolicy() {
    }

    public static boolean isGranted(int sdkInt, boolean permissionGranted) {
        return sdkInt < ANDROID_17_API || permissionGranted;
    }

    public static boolean canAccess(int sdkInt, boolean permissionGranted, String url) {
        return isLoopback(url) || isGranted(sdkInt, permissionGranted);
    }

    public static boolean isLoopback(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host != null && host.length() > 1 && host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
