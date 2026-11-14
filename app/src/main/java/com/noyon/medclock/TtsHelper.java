package com.noyon.medclock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The phone's own text-to-speech.
 * Prefers Google's engine (it has Bangla); many phones ship another default engine without Bangla,
 * which is why a phone can stay silent. Reports what it found so the app can guide the user.
 */
public class TtsHelper {
    public interface Done { void run(); }

    static final String GOOGLE = "com.google.android.tts";

    private final Context ctx;
    private final boolean alarm;
    private TextToSpeech tts;
    private boolean initDone = false;
    private boolean ready = false;
    private int initStatus = -99;
    private String engineUsed = "";
    private Locale bnLocale = null;
    private int bnStatus = -2;
    private String currentLang = "";
    private int lastError = 0;
    private String lastErrorWhere = "";
    private int spoken = 0;
    private int seq = 0;
    private final List<Runnable> pending = new ArrayList<>();
    private final Map<String, Done> callbacks = new HashMap<>();

    public TtsHelper(Context c, boolean alarmStream) {
        ctx = c.getApplicationContext();
        alarm = alarmStream;
        start(isInstalled(GOOGLE) ? GOOGLE : null);
    }

    static boolean isInstalledStatic(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private boolean isInstalled(String pkg) { return isInstalledStatic(ctx, pkg); }

    private void start(String engine) {
        TextToSpeech.OnInitListener l = status -> onInit(status, engine);
        tts = engine == null ? new TextToSpeech(ctx, l) : new TextToSpeech(ctx, l, engine);
    }

    private void onInit(int status, String engine) {
        initStatus = status;
        ready = status == TextToSpeech.SUCCESS;
        if (!ready && engine != null) {
            // Google engine failed to start: fall back to the phone's default engine
            try { tts.shutdown(); } catch (Exception ignored) { }
            start(null);
            return;
        }
        if (ready) {
            try { engineUsed = String.valueOf(tts.getDefaultEngine()); } catch (Exception ignored) { }
            if (engine != null) engineUsed = engine;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { spoken++; }
                @Override public void onDone(String id) { fire(id); }
                @Override public void onError(String id) { lastError = -1; lastErrorWhere = "utterance"; fire(id); }
                @Override public void onError(String id, int code) { lastError = code; lastErrorWhere = "utterance"; fire(id); }
                @Override public void onStop(String id, boolean interrupted) { fire(id); }
            });
            findBangla();
        }
        List<Runnable> copy;
        synchronized (pending) {
            initDone = true;
            copy = new ArrayList<>(pending);
            pending.clear();
        }
        for (Runnable r : copy) r.run();
    }

    private void findBangla() {
        Locale[] options = { new Locale("bn", "BD"), new Locale("bn", "IN"), new Locale("bn") };
        bnLocale = null; bnStatus = TextToSpeech.LANG_NOT_SUPPORTED;
        for (Locale l : options) {
            try {
                int s = tts.isLanguageAvailable(l);
                if (s >= TextToSpeech.LANG_AVAILABLE) { bnLocale = l; bnStatus = s; return; }
                if (s == TextToSpeech.LANG_MISSING_DATA) bnStatus = s;   // engine knows Bangla, voice not downloaded
            } catch (Exception ignored) { }
        }
    }

    public boolean hasBangla() { return ready && bnLocale != null; }

    private void fire(String id) {
        Done d;
        synchronized (callbacks) { d = callbacks.remove(id); }
        if (d != null) d.run();
    }

    private boolean setLang(String lang) {
        Locale l = "bn".equals(lang) ? bnLocale : Locale.US;
        if (l == null) return false;
        if (lang.equals(currentLang)) return true;
        int r = TextToSpeech.LANG_NOT_SUPPORTED;
        try { r = tts.setLanguage(l); } catch (Exception ignored) { }
        if (r >= TextToSpeech.LANG_AVAILABLE) { currentLang = lang; return true; }
        lastError = r; lastErrorWhere = "setLanguage " + l;
        return false;
    }

    /** Speaks text; if Bangla is not available on this phone, speaks the English text instead (when given). */
    public void speak(final String text, final String lang, final float rate, final String fallbackEn, final Done done) {
        Runnable r = () -> {
            if (!ready) { lastError = initStatus; lastErrorWhere = "init"; if (done != null) done.run(); return; }
            String say = text, l = lang == null ? "bn" : lang;
            if ("bn".equals(l) && !setLang("bn")) {
                findBangla();   // the voice may have been downloaded since
                if (!setLang("bn")) {
                    if (fallbackEn == null || fallbackEn.isEmpty()) { if (done != null) done.run(); return; }
                    say = fallbackEn; l = "en";
                    if (!setLang("en")) { if (done != null) done.run(); return; }
                }
            } else if (!"bn".equals(l) && !setLang(l)) {
                if (done != null) done.run(); return;
            }
            tts.setSpeechRate(rate <= 0 ? 0.85f : rate);
            String id = "u" + (seq++);
            if (done != null) synchronized (callbacks) { callbacks.put(id, done); }
            int res = tts.speak(say, TextToSpeech.QUEUE_ADD, new Bundle(), id);
            if (res != TextToSpeech.SUCCESS) { lastError = res; lastErrorWhere = "speak"; fire(id); }
        };
        synchronized (pending) {
            if (!initDone) { pending.add(r); return; }
        }
        r.run();
    }

    public void speak(String text, String lang, float rate, Done done) { speak(text, lang, rate, null, done); }

    public void stop() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        List<Done> left;
        synchronized (callbacks) { left = new ArrayList<>(callbacks.values()); callbacks.clear(); }
        for (Done d : left) d.run();
    }

    public void shutdown() {
        stop();
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
    }

    /** Everything the voice-check screen shows. bn: 1 ready, 0 needs download, -1 not supported, -2 still starting. */
    public String info() {
        JSONObject o = new JSONObject();
        try {
            if (ready) findBangla();
            o.put("ready", ready);
            o.put("initDone", initDone);
            o.put("bn", !initDone ? -2 : bnLocale != null ? 1 : bnStatus == TextToSpeech.LANG_MISSING_DATA ? 0 : -1);
            o.put("bnLocale", bnLocale == null ? "" : bnLocale.toString());
            o.put("engine", engineUsed);
            o.put("google", isInstalled(GOOGLE));
            JSONArray engines = new JSONArray();
            if (ready) for (TextToSpeech.EngineInfo e : tts.getEngines()) engines.put(e.name);
            o.put("engines", engines);
            o.put("lastError", lastError);
            o.put("lastErrorWhere", lastErrorWhere);
            o.put("spoken", spoken);
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                o.put("media", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                o.put("mediaMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                o.put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM));
                o.put("alarmMax", am.getStreamMaxVolume(AudioManager.STREAM_ALARM));
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    public String engine() { return engineUsed; }
}
