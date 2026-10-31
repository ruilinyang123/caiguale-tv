package com.fongmi.android.tv.utils;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.StringRes;
import androidx.core.app.AlarmManagerCompat;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.EpgReminderRecord;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.receiver.EpgReminderReceiver;

import java.util.List;

public class EpgReminder {

    private static final String CHANNEL_EPG = "epg_reminder";
    private static final long LEAD_MS = 5 * 60 * 1000;
    private static final long MIN_LEAD_MS = 60 * 1000;

    public static void createChannel() {
        try {
            NotificationManagerCompat.from(App.get()).createNotificationChannel(
                    new NotificationChannelCompat.Builder(CHANNEL_EPG, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                            .setName("节目提醒").build());
        } catch (Exception ignored) {
        }
    }

    public static boolean isScheduled(String title) {
        try {
            return AppDatabase.get().getEpgReminderDao().exists(title);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Adds a reminder for a program that has not started yet, or removes the existing one.
     *
     * @return string resource describing the outcome, ready for {@link Notify#show(int)}
     */
    @StringRes
    public static int toggle(String channelName, EpgData program) {
        String title = program.getTitle();
        if (isScheduled(title)) {
            cancel(title);
            return R.string.epg_reminder_cancelled;
        }
        long triggerAt = program.getStartTime() - LEAD_MS;
        if (triggerAt <= System.currentTimeMillis() + MIN_LEAD_MS) return R.string.epg_reminder_too_late;
        if (!canSchedule()) {
            requestPermission();
            return R.string.epg_reminder_no_permission;
        }
        return schedule(channelName, program, triggerAt) ? R.string.epg_reminder_added : R.string.epg_reminder_failed;
    }

    public static boolean canSchedule() {
        AlarmManager am = (AlarmManager) App.get().getSystemService(Context.ALARM_SERVICE);
        return am != null && AlarmManagerCompat.canScheduleExactAlarms(am);
    }

    private static void requestPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + App.get().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            App.get().startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    public static boolean schedule(String channelName, EpgData program, long triggerAtMillis) {
        if (triggerAtMillis <= System.currentTimeMillis() + MIN_LEAD_MS) return false;
        Context ctx = App.get();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || !AlarmManagerCompat.canScheduleExactAlarms(am)) return false;
        Intent intent = new Intent(ctx, EpgReminderReceiver.class);
        intent.putExtra("channelName", channelName);
        intent.putExtra("programTitle", program.getTitle());
        intent.putExtra("programStart", program.getStart());
        PendingIntent pi = PendingIntent.getBroadcast(ctx, program.getTitle().hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } catch (RuntimeException e) {
            return false;
        }
        try {
            EpgReminderRecord record = new EpgReminderRecord();
            record.setChannelName(channelName);
            record.setProgramTitle(program.getTitle());
            record.setProgramStart(program.getStart());
            record.setTriggerAtMillis(triggerAtMillis);
            AppDatabase.get().getEpgReminderDao().insert(record);
        } catch (Exception ignored) {
        }
        return true;
    }

    public static void cancel(String title) {
        Context ctx = App.get();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, EpgReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, title.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
        try {
            AppDatabase.get().getEpgReminderDao().delete(title);
        } catch (Exception ignored) {
        }
    }

    public static void rebuildFromStorage() {
        try {
            long now = System.currentTimeMillis();
            AppDatabase.get().getEpgReminderDao().deleteExpired(now);
            List<EpgReminderRecord> records = AppDatabase.get().getEpgReminderDao().findPending(now);
            for (EpgReminderRecord r : records) {
                if (r.getTriggerAtMillis() <= now + 30000) {
                    AppDatabase.get().getEpgReminderDao().delete(r.getProgramTitle());
                    continue;
                }
                Context ctx = App.get();
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (am == null || !AlarmManagerCompat.canScheduleExactAlarms(am)) continue;
                Intent intent = new Intent(ctx, EpgReminderReceiver.class);
                intent.putExtra("channelName", r.getChannelName());
                intent.putExtra("programTitle", r.getProgramTitle());
                intent.putExtra("programStart", r.getProgramStart());
                PendingIntent pi = PendingIntent.getBroadcast(ctx, r.getProgramTitle().hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.getTriggerAtMillis(), pi);
            }
        } catch (Exception ignored) {
        }
    }

    public static Notification buildNotification(String channelName, String programTitle, String startTime) {
        return new NotificationCompat.Builder(App.get(), CHANNEL_EPG)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(channelName + " - " + programTitle)
                .setContentText("即将开始: " + startTime)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build();
    }
}
