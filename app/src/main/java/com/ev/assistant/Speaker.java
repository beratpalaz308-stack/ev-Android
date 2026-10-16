package com.ev.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Locale;

/** Metni sesli okur. Tüm çağrılar ana iş parçacığından yapılmalı. */
final class Speaker {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready = false;
    private String applied = "";
    private Runnable onDone;
    private final Runnable watchdog = this::finish;

    Speaker(Context c) {
        ctx = c.getApplicationContext();
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts.setLanguage(new Locale("tr", "TR"));
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { }
                        @Override public void onDone(String id) { main.post(() -> finish()); }
                        @Override public void onError(String id) { main.post(() -> finish()); }
                    });
                    ready = true;
                } catch (Exception ignored) { }
            }
        });
    }

    void speak(String text, Runnable done) {
        main.removeCallbacks(watchdog);
        onDone = done;
        if (!ready) {
            main.postDelayed(watchdog, 600);
            return;
        }
        applyVoice();
        main.postDelayed(watchdog, 45000);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ev" + System.nanoTime());
        if (r != TextToSpeech.SUCCESS) finish();
    }

    private void finish() {
        main.removeCallbacks(watchdog);
        Runnable d = onDone;
        onDone = null;
        if (d != null) d.run();
    }

    private void applyVoice() {
        try {
            String want = ctx.getSharedPreferences("ev", Context.MODE_PRIVATE).getString("voice", "");
            if (want.isEmpty() || want.equals(applied)) return;
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(want)) {
                    tts.setVoice(v);
                    applied = want;
                    return;
                }
            }
        } catch (Exception ignored) { }
    }
}
