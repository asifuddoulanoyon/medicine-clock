package com.noyon.medclock;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/** Hosts the app page and gives it the phone's voice, alarms and settings. */
public class MainActivity extends Activity {
    private static final int FILE_REQ = 11;
    private WebView web;
    private TtsHelper tts;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        AlarmReceiver.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        tts = new TtsHelper(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = p.createIntent();
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, null), FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != FILE_REQ || fileCb == null) return;
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(result);
        fileCb = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    private void open(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) {
            try {
                Intent app = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(app);
            } catch (Exception ignored) { }
        }
    }

    /** Methods the page can call as window.AndroidApp.* */
    class Bridge {
        @JavascriptInterface public boolean isNative() { return true; }

        @JavascriptInterface
        public void speak(String id, String text, String lang, float rate) {
            tts.speak(text, lang, rate, () -> runOnUiThread(() ->
                    web.evaluateJavascript("window.__ttsDone && window.__ttsDone(" + JSONObject.quote(id) + ")", null)));
        }

        @JavascriptInterface public void stopSpeak() { tts.stop(); }

        @JavascriptInterface public String ttsInfo() { return tts.info(); }

        @JavascriptInterface
        public void openTtsSettings() { open(new Intent("com.android.settings.TTS_SETTINGS")); }

        @JavascriptInterface
        public void setSchedule(String json) { Scheduler.setSchedule(MainActivity.this, json); }

        @JavascriptInterface
        public String takeLogs() { return Scheduler.takeLogs(MainActivity.this); }

        @JavascriptInterface
        public void saveClip(String id, String dataUrl) {
            try {
                if (id == null || id.contains("/") || dataUrl == null) return;
                int comma = dataUrl.indexOf(',');
                byte[] bytes = Base64.decode(comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl, Base64.DEFAULT);
                File dir = new File(getFilesDir(), "clips");
                if (!dir.exists() && !dir.mkdirs()) return;
                try (FileOutputStream out = new FileOutputStream(new File(dir, id))) { out.write(bytes); }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void deleteClip(String id) {
            if (id == null || id.contains("/")) return;
            File f = new File(new File(getFilesDir(), "clips"), id);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }

        @JavascriptInterface
        public void testAlarm(String eventJson) {
            Intent i = new Intent(MainActivity.this, AlarmActivity.class);
            i.putExtra("event", eventJson);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public String status() {
            JSONObject o = new JSONObject();
            try {
                Context c = MainActivity.this;
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                o.put("exact", Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms()));
                o.put("notif", nm != null && nm.areNotificationsEnabled());
                o.put("fullscreen", Build.VERSION.SDK_INT < 34 || (nm != null && nm.canUseFullScreenIntent()));
                o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void fixSetting(String which) {
            Uri pkg = Uri.parse("package:" + getPackageName());
            switch (which == null ? "" : which) {
                case "exact":
                    if (Build.VERSION.SDK_INT >= 31) open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg));
                    break;
                case "battery":
                    open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg));
                    break;
                case "fullscreen":
                    if (Build.VERSION.SDK_INT >= 34) open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg));
                    break;
                case "notif":
                default:
                    Intent n = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    n.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    open(n);
            }
        }
    }
}
