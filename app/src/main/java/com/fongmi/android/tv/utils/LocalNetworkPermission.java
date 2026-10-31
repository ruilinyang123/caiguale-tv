package com.fongmi.android.tv.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.PermissionCallback;
import com.permissionx.guolindev.PermissionX;

import java.util.function.Consumer;

public final class LocalNetworkPermission {

    public static final String PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK;

    private LocalNetworkPermission() {
    }

    public static boolean isGranted(Context context) {
        boolean granted = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED;
        return LocalNetworkPermissionPolicy.isGranted(Build.VERSION.SDK_INT, granted);
    }

    public static boolean canAccess(Context context, String url) {
        boolean granted = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED;
        return LocalNetworkPermissionPolicy.canAccess(Build.VERSION.SDK_INT, granted, url);
    }

    public static void request(FragmentActivity activity, Consumer<Boolean> callback) {
        if (isGranted(activity)) {
            onResult(callback, true);
            return;
        }
        activity.getWindow().getDecorView().post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            PermissionX.init(activity).permissions(PERMISSION).request(new PermissionCallback(granted -> onResult(callback, granted)));
        });
    }

    public static void request(Fragment fragment, Consumer<Boolean> callback) {
        FragmentActivity activity = fragment.requireActivity();
        if (isGranted(activity)) {
            onResult(callback, true);
            return;
        }
        activity.getWindow().getDecorView().post(() -> {
            if (!fragment.isAdded() || activity.isFinishing() || activity.isDestroyed()) return;
            PermissionX.init(fragment).permissions(PERMISSION).request(new PermissionCallback(granted -> onResult(callback, granted)));
        });
    }

    private static void onResult(Consumer<Boolean> callback, boolean granted) {
        if (granted) NsdDeviceDiscovery.register();
        else Notify.show(R.string.local_network_permission_denied);
        if (callback != null) callback.accept(granted);
    }
}
