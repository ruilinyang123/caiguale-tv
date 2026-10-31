package com.fongmi.android.tv.remote;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;

/** Foreground host for the opt-in relay agent; Android may still stop it. */
public final class RemoteAgentService extends Service {
    private static final String ACTION_START = BuildConfig.APPLICATION_ID + ".remote.START";
    private static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".remote.STOP";
    private static final int NOTIFICATION_ID = 9531;

    public static void start(Context context) {
        try { ContextCompat.startForegroundService(context, new Intent(context, RemoteAgentService.class).setAction(ACTION_START)); }
        catch (Throwable ignored) { /* Agent remains functional through its scheduler if foreground start is unavailable. */ }
    }

    public static void stop(Context context) { context.stopService(new Intent(context, RemoteAgentService.class).setAction(ACTION_STOP)); }

    @Override public void onCreate() {
        super.onCreate();
        try { startForeground(NOTIFICATION_ID, notification()); }
        catch (Throwable ignored) { stopSelf(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        RemoteAgent.get().stop();
        super.onDestroy();
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pending = launch == null ? null : PendingIntent.getActivity(this, 9531, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, Notify.DEFAULT)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.remote_public_title))
                .setContentText(getString(R.string.remote_public_running)).setOngoing(true).setContentIntent(pending).build();
    }
}
