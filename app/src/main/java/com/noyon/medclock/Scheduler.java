package com.noyon.medclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the list of upcoming alarms (sent by the app page) and always has the
 * next one booked with AlarmManager, so alarms ring even when the app is closed.
 */
public class Scheduler {
    private static final int REQ_ALARM = 1001;
    private static final int REQ_SHOW = 1002;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("medclock", Context.MODE_PRIVATE);
    }

    static JSONArray arr(Context c, String key) {
        try { return new JSONArray(prefs(c).getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static Set<String> set(Context c, String key) {
        return new HashSet<>(prefs(c).getStringSet(key, new HashSet<>()));
    }

    static synchronized void setSchedule(Context c, String json) {
        JSONArray events;
        try { events = new JSONArray(json); } catch (Exception e) { return; }
        long now = System.currentTimeMillis();
        // Keep only bookkeeping that still matters
        Set<String> ids = new HashSet<>(), keys = new HashSet<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            ids.add(e.optString("id"));
            JSONArray k = e.optJSONArray("keys");
            if (k != null) for (int j = 0; j < k.length(); j++) keys.add(k.optString(j));
        }
        JSONArray snoozes = arr(c, "snoozes"), keptSnoozes = new JSONArray();
        for (int i = 0; i < snoozes.length(); i++) {
            JSONObject s = snoozes.optJSONObject(i);
            if (s != null && s.optLong("at") > now - 3600000L) { keptSnoozes.put(s); ids.add(s.optString("id")); }
        }
        Set<String> fired = set(c, "fired"); fired.retainAll(ids);
        Set<String> done = set(c, "doneKeys"); done.retainAll(keys);
        prefs(c).edit()
                .putString("schedule", events.toString())
                .putString("snoozes", keptSnoozes.toString())
                .putStringSet("fired", fired)
                .putStringSet("doneKeys", done)
                .apply();
        scheduleNext(c);
    }

    static synchronized void markFired(Context c, String id) {
        Set<String> s = set(c, "fired"); s.add(id);
        prefs(c).edit().putStringSet("fired", s).apply();
    }

    static synchronized void markDone(Context c, JSONArray keys) {
        if (keys == null) return;
        Set<String> s = set(c, "doneKeys");
        for (int i = 0; i < keys.length(); i++) s.add(keys.optString(i));
        prefs(c).edit().putStringSet("doneKeys", s).apply();
    }

    static synchronized void addLog(Context c, JSONArray keys, String status) {
        if (keys == null) return;
        JSONArray logs = arr(c, "logs");
        long now = System.currentTimeMillis();
        for (int i = 0; i < keys.length(); i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("key", keys.optString(i)); o.put("s", status); o.put("at", now);
                logs.put(o);
            } catch (Exception ignored) { }
        }
        prefs(c).edit().putString("logs", logs.toString()).apply();
    }

    static synchronized String takeLogs(Context c) {
        String s = prefs(c).getString("logs", "[]");
        prefs(c).edit().putString("logs", "[]").apply();
        return s;
    }

    static synchronized void snooze(Context c, JSONObject ev, int minutes) {
        try {
            long now = System.currentTimeMillis();
            JSONObject copy = new JSONObject(ev.toString());
            String base = ev.optString("id").replaceAll("-s\\d+$", "");
            copy.put("id", base + "-s" + now);
            copy.put("at", now + minutes * 60000L);
            JSONArray sn = arr(c, "snoozes"); sn.put(copy);
            prefs(c).edit().putString("snoozes", sn.toString()).apply();
        } catch (Exception ignored) { }
        scheduleNext(c);
    }

    private static boolean allDone(JSONObject e, Set<String> done) {
        JSONArray k = e.optJSONArray("keys");
        if (k == null || k.length() == 0) return false;
        for (int i = 0; i < k.length(); i++) if (!done.contains(k.optString(i))) return false;
        return true;
    }

    static JSONObject next(Context c) {
        long now = System.currentTimeMillis();
        Set<String> fired = set(c, "fired"), done = set(c, "doneKeys");
        JSONObject best = null; long bestAt = Long.MAX_VALUE;
        for (String key : new String[]{"schedule", "snoozes"}) {
            JSONArray a = arr(c, key);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                long at = e.optLong("at");
                if (at < now - 30 * 60000L) continue;          // too old: missed while phone was off
                if (fired.contains(e.optString("id"))) continue;
                if (allDone(e, done)) continue;
                if (at < bestAt) { best = e; bestAt = at; }
            }
        }
        return best;
    }

    static void scheduleNext(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        JSONObject ev = next(c);
        Intent i = new Intent(c, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (ev == null) {
            am.cancel(PendingIntent.getBroadcast(c, REQ_ALARM, i, flags));
            return;
        }
        i.putExtra("event", ev.toString());
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_ALARM, i, flags);
        long at = Math.max(ev.optLong("at"), System.currentTimeMillis() + 1500);
        PendingIntent show = PendingIntent.getActivity(c, REQ_SHOW, new Intent(c, MainActivity.class), flags);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
