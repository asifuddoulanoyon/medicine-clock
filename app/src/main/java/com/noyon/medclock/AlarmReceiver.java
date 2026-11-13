package com.noyon.medclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import org.json.JSONObject;

/** Fires at medicine time: shows a full-screen alarm (works over the lock screen). */
public class AlarmReceiver extends BroadcastReceiver {
    static final String CHANNEL = "medicine_alarm_v1";
    static final int NOTIF_ID = 77;

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "ওষুধের অ্যালার্ম", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("ওষুধ খাওয়ার সময় হলে অ্যালার্ম");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        ch.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    static void showAlarm(Context c, String eventJson) {
        ensureChannel(c);
        String title = "ওষুধের সময়!", text = "";
        try {
            JSONObject e = new JSONObject(eventJson);
            title = e.optString("title", title);
            text = e.optString("count", "");
        } catch (Exception ignored) { }

        Intent full = new Intent(c, AlarmActivity.class);
        full.putExtra("event", eventJson);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pi = PendingIntent.getActivity(c, (int) (System.currentTimeMillis() % 100000), full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(true)
                .build();
        n.flags |= Notification.FLAG_INSISTENT;   // keeps ringing until opened
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);

        // If allowed, open the alarm screen straight away too
        try { c.startActivity(full); } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String ev = intent.getStringExtra("event");
        if (ev != null) {
            try { Scheduler.markFired(c, new JSONObject(ev).optString("id")); } catch (Exception ignored) { }
            showAlarm(c, ev);
        }
        Scheduler.scheduleNext(c);
    }
}
