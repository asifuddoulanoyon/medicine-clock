package com.noyon.medclock;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Phone's own text-to-speech, played on the alarm stream so it is loud even when media volume is low. */
public class TtsHelper {
    public interface Done { void run(); }

    private TextToSpeech tts;
    private boolean initDone = false;
    private boolean ready = false;
    private String currentLang = "";
    private int seq = 0;
    private final List<Runnable> pending = new ArrayList<>();
    private final Map<String, Done> callbacks = new HashMap<>();

    public TtsHelper(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { fire(id); }
                    @Override public void onError(String id) { fire(id); }
                    @Override public void onError(String id, int code) { fire(id); }
                    @Override public void onStop(String id, boolean interrupted) { fire(id); }
                });
            }
            List<Runnable> copy;
            synchronized (pending) {
                initDone = true;
                copy = new ArrayList<>(pending);
                pending.clear();
            }
            for (Runnable r : copy) r.run();
        });
    }

    private void fire(String id) {
        Done d;
        synchronized (callbacks) { d = callbacks.remove(id); }
        if (d != null) d.run();
    }

    private Locale banglaLocale() {
        Locale[] options = { new Locale("bn", "BD"), new Locale("bn", "IN"), new Locale("bn") };
        for (Locale l : options) {
            try {
                if (tts.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) return l;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void setLang(String lang) {
        if (lang.equals(currentLang)) return;
        currentLang = lang;
        Locale l = "bn".equals(lang) ? banglaLocale() : Locale.US;
        if (l == null) l = new Locale("bn", "BD");
        try { tts.setLanguage(l); } catch (Exception ignored) { }
    }

    public void speak(final String text, final String lang, final float rate, final Done done) {
        Runnable r = () -> {
            if (!ready) { if (done != null) done.run(); return; }
            setLang(lang == null ? "bn" : lang);
            tts.setSpeechRate(rate <= 0 ? 0.85f : rate);
            String id = "u" + (seq++);
            if (done != null) synchronized (callbacks) { callbacks.put(id, done); }
            int res = tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), id);
            if (res != TextToSpeech.SUCCESS) fire(id);
        };
        synchronized (pending) {
            if (!initDone) { pending.add(r); return; }
        }
        r.run();
    }

    public void stop() {
        try { tts.stop(); } catch (Exception ignored) { }
        List<Done> left;
        synchronized (callbacks) { left = new ArrayList<>(callbacks.values()); callbacks.clear(); }
        for (Done d : left) d.run();
    }

    public void shutdown() {
        stop();
        try { tts.shutdown(); } catch (Exception ignored) { }
    }

    /** {ready, bn: -1 missing / 0+ available, engine} */
    public String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", ready);
            int bn = -2;
            if (ready) {
                Locale l = banglaLocale();
                bn = l == null ? -1 : tts.isLanguageAvailable(l);
            }
            o.put("bn", bn);
            o.put("engine", ready ? String.valueOf(tts.getDefaultEngine()) : "");
        } catch (Exception ignored) { }
        return o.toString();
    }
}
