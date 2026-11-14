package com.noyon.medclock;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * The alarm screen: rings, then speaks every medicine (name, how much, before/after food),
 * then rings again, a few rounds, until "taken", "snooze" or "skip" is pressed.
 */
public class AlarmActivity extends Activity {
    private static final int RED = Color.parseColor("#C8412C");
    private JSONObject ev;
    private TtsHelper tts;
    private MediaPlayer ring, clip;
    private Vibrator vib;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int rounds = 0;
    private boolean closed = false;
    private int speakGen = 0;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(AlarmReceiver.NOTIF_ID);   // stop the notification's own ringing

        try { ev = new JSONObject(getIntent().getStringExtra("event")); }
        catch (Exception e) { finish(); return; }

        buildUi();
        tts = new TtsHelper(this, true);
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max * 0.8) {
                try { am.setStreamVolume(AudioManager.STREAM_ALARM, (int) Math.ceil(max * 0.9), 0); } catch (Exception ignored) { }
            }
        }
        h.postDelayed(this::round, 400);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Another alarm arrived while this one is showing: show the newer one
        String e = intent.getStringExtra("event");
        if (e == null) return;
        try { ev = new JSONObject(e); } catch (Exception ignored) { return; }
        stopSound();
        rounds = 0;
        buildUi();
        h.postDelayed(this::round, 400);
    }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView text(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 22 : 18);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? RED : Color.WHITE);
        GradientDrawable bg = rounded(primary ? Color.WHITE : Color.TRANSPARENT, 16);
        if (!primary) bg.setStroke((int) dp(2), Color.argb(190, 255, 255, 255));
        b.setBackground(bg);
        b.setPadding(0, (int) dp(primary ? 18 : 12), 0, (int) dp(primary ? 18 : 12));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) dp(top);
        return p;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(RED);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(20);
        col.setPadding(pad, (int) dp(36), pad, pad);
        sv.addView(col);

        TextView bell = text("⏰", 54, false, Color.WHITE);
        bell.setGravity(Gravity.CENTER);
        col.addView(bell, lp(0));
        TextView title = text(ev.optString("title"), 30, true, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        col.addView(title, lp(4));
        TextView time = text(ev.optString("time"), 20, false, Color.WHITE);
        time.setGravity(Gravity.CENTER);
        col.addView(time, lp(2));
        TextView count = text(ev.optString("count"), 19, true, Color.WHITE);
        count.setGravity(Gravity.CENTER);
        count.setBackground(rounded(Color.argb(50, 255, 255, 255), 30));
        count.setPadding((int) dp(16), (int) dp(6), (int) dp(16), (int) dp(6));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER_HORIZONTAL; cp.topMargin = (int) dp(10);
        col.addView(count, cp);

        String first = ev.optString("first", "");
        if (!first.isEmpty()) {
            TextView f = text(first, 18, true, RED);
            f.setBackground(rounded(Color.WHITE, 14));
            f.setPadding((int) dp(14), (int) dp(12), (int) dp(14), (int) dp(12));
            col.addView(f, lp(14));
        }

        JSONArray items = ev.optJSONArray("items");
        int n = items == null ? 0 : items.length();
        for (int i = 0; i < n; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setBackground(rounded(Color.argb(40, 255, 255, 255), 16));
            box.setPadding((int) dp(14), (int) dp(12), (int) dp(14), (int) dp(12));
            if (n > 1) {
                TextView no = text(String.valueOf(i + 1), 30, true, Color.WHITE);
                no.setPadding(0, 0, (int) dp(14), 0);
                box.addView(no);
            }
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(text(it.optString("name"), 24, true, Color.WHITE));
            txt.addView(text(it.optString("line"), 18, true, Color.WHITE));
            String sub = it.optString("sub", "");
            if (!sub.isEmpty()) txt.addView(text(sub, 15, false, Color.argb(225, 255, 255, 255)));
            String note = it.optString("note", "");
            if (!note.isEmpty()) txt.addView(text(note, 15, false, Color.argb(225, 255, 255, 255)));
            box.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(box, lp(12));
        }

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        JSONObject lb = ev.optJSONObject("labels");
        if (lb == null) lb = new JSONObject();
        col.addView(button(lb.optString("taken", "নিয়েছি ✓"), true, v -> finishWith("taken")), lp(18));
        col.addView(button(lb.optString("again", "আবার শুনুন"), false, v -> { stopSound(); speakAll(() -> h.postDelayed(this::round, 20000)); }), lp(10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = (int) dp(10);
        row.addView(button(lb.optString("snooze", "১০ মিনিট পরে"), false, v -> finishWith("snooze")), half);
        row.addView(button(lb.optString("skip", "বাদ দিন"), false, v -> finishWith("skipped")), half2);
        col.addView(row, lp(10));

        setContentView(sv);
    }

    /** One round: ring for a few seconds, then speak everything, then wait and repeat. */
    private void round() {
        if (closed) return;
        if (rounds >= 5) { stopSound(); return; }
        rounds++;
        startRing();
        h.postDelayed(() -> {
            if (closed) return;
            stopRing();
            speakAll(() -> h.postDelayed(this::round, 20000));
        }, 6000);
    }

    private void startRing() {
        stopRing();
        try {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ring = new MediaPlayer();
            ring.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ring.setDataSource(this, u);
            ring.setLooping(true);
            ring.prepare();
            ring.start();
        } catch (Exception e) { ring = null; }
        try {
            if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 400, 700, 400}, 0));
        } catch (Exception ignored) { }
    }

    private void stopRing() {
        if (ring != null) { try { ring.stop(); ring.release(); } catch (Exception ignored) { } ring = null; }
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
    }

    private void stopSound() {
        h.removeCallbacksAndMessages(null);
        speakGen++;
        stopRing();
        if (tts != null) tts.stop();
        if (clip != null) { try { clip.stop(); clip.release(); } catch (Exception ignored) { } clip = null; }
    }

    private void speakAll(Runnable after) {
        final int gen = ++speakGen;
        final JSONArray parts = ev.optJSONArray("parts");
        final String lang = ev.optString("lang", "bn");
        final float rate = (float) ev.optDouble("rate", 0.85);
        speakPart(parts, 0, lang, rate, gen, after);
    }

    private void speakPart(JSONArray parts, int i, String lang, float rate, int gen, Runnable after) {
        if (closed || gen != speakGen) return;
        if (parts == null || i >= parts.length()) { if (after != null) h.post(after); return; }
        JSONObject p = parts.optJSONObject(i);
        Runnable next = () -> h.postDelayed(() -> speakPart(parts, i + 1, lang, rate, gen, after), 350);
        if (p == null) { next.run(); return; }
        // Built-in Bangla phrase recorded into the app
        String phrase = p.optString("c", "");
        if (!phrase.isEmpty()) {
            Runnable quick = () -> h.postDelayed(() -> speakPart(parts, i + 1, lang, rate, gen, after), 110);
            if (!playAsset("voice/" + phrase + ".mp3", quick)) quick.run();
            return;
        }
        // The family's own recording for this medicine
        String clipId = p.optString("a", "");
        if (!clipId.isEmpty()) {
            File f = new File(new File(getFilesDir(), "clips"), clipId);
            if (f.exists()) {
                try {
                    clip = new MediaPlayer();
                    clip.setAudioAttributes(alarmAttrs());
                    clip.setDataSource(f.getAbsolutePath());
                    clip.setOnCompletionListener(mp -> { mp.release(); clip = null; next.run(); });
                    clip.prepare();
                    clip.start();
                    return;
                } catch (Exception e) { clip = null; }
            }
            next.run();
            return;
        }
        // Text for the phone's voice (medicine names, notes)
        String text = p.optString("t", "");
        if (text.isEmpty()) { next.run(); return; }
        String l = p.optString("l", "");
        if (l.isEmpty()) l = lang;
        String fb = p.optString("fb", ""), alt = p.optString("alt", "");
        if ("bn".equals(l) && !tts.hasBangla()) {
            if (!fb.isEmpty()) { text = fb; l = "en"; }
            else {
                if (alt.isEmpty() || !playAsset("voice/" + alt + ".mp3", next)) next.run();
                return;
            }
        }
        tts.speak(text, l, rate, fb, () -> h.post(next));
    }

    private AudioAttributes alarmAttrs() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    /** Plays a recorded phrase packed in the app. Returns false if it could not start. */
    private boolean playAsset(String path, Runnable done) {
        try {
            android.content.res.AssetFileDescriptor afd = getAssets().openFd(path);
            clip = new MediaPlayer();
            clip.setAudioAttributes(alarmAttrs());
            clip.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            clip.setOnCompletionListener(mp -> { mp.release(); if (clip == mp) clip = null; done.run(); });
            clip.setOnErrorListener((mp, what, extra) -> { mp.release(); if (clip == mp) clip = null; done.run(); return true; });
            clip.prepare();
            clip.start();
            return true;
        } catch (Exception e) {
            if (clip != null) { try { clip.release(); } catch (Exception ignored) { } clip = null; }
            return false;
        }
    }

    private void finishWith(String action) {
        if (closed) return;
        closed = true;
        stopSound();
        boolean test = ev.optBoolean("test", false);
        JSONArray keys = ev.optJSONArray("keys");
        if (!test) {
            if ("snooze".equals(action)) {
                Scheduler.snooze(this, ev, 10);
            } else {
                Scheduler.addLog(this, keys, action);
                Scheduler.markDone(this, keys);
                Scheduler.scheduleNext(this);
            }
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // The alarm needs an answer: taken, snooze or skip
    }

    @Override
    protected void onDestroy() {
        closed = true;
        stopSound();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
