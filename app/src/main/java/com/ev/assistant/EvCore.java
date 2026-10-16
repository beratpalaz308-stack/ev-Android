package com.ev.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Hem arka plan servisinin hem de ekranların paylaştığı tek beyin. */
final class EvCore {
    interface Listener {
        void onMessage(boolean user, String text);

        void onState(String state);
    }

    private static EvCore inst;

    static synchronized EvCore get(Context c) {
        if (inst == null) inst = new EvCore(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Speaker speaker;
    private final List<Object[]> log = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<String[]> hist = new ArrayList<>();
    private volatile boolean busy = false;
    private volatile boolean improving = false;
    private volatile String lastState = "Hazır";

    private EvCore(Context c) {
        app = c;
        speaker = new Speaker(c);
    }

    // ------------------------------------------------------------ ekran bağlantısı

    boolean isBusy() {
        return busy;
    }

    String lastState() {
        return lastState;
    }

    boolean speakEnabled() {
        return app.getSharedPreferences("ev", Context.MODE_PRIVATE).getBoolean("speak", true);
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Ana iş parçacığında çağır. */
    List<Object[]> snapshot() {
        return new ArrayList<>(log);
    }

    void addMessage(final boolean user, final String text) {
        main.post(() -> {
            log.add(new Object[]{user, text});
            while (log.size() > 100) log.remove(0);
            for (Listener l : new ArrayList<>(listeners)) l.onMessage(user, text);
        });
    }

    private void state(final String s) {
        lastState = s;
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onState(s);
        });
    }

    private static String str(JSONObject p, String k) {
        return Actions.str(p, k);
    }

    // ------------------------------------------------------------ yazı ve fotoğraf

    /** Ana iş parçacığından çağır. */
    void askTyped(String text, byte[] jpeg) {
        if (busy) {
            state("EV meşgul, bir saniye...");
            return;
        }
        ask(text, null, jpeg, speakEnabled(), null);
    }

    // ------------------------------------------------------------ ses

    /** Bir konuşma turu: (isteğe bağlı "Efendim"), kayıt, Gemini, cevap. Ana iş parçacığından çağır. */
    void voiceTurn(final boolean greet, final Runnable after) {
        busy = true;
        state("Dinliyorum...");
        if (greet) {
            speaker.speak("Efendim", () -> record(after));
        } else {
            record(after);
        }
    }

    private void record(final Runnable after) {
        new Thread(() -> {
            byte[] wav = null;
            try {
                Thread.sleep(150);
                wav = Recorder.record(6000, 12000);
            } catch (Exception ignored) { }
            if (wav == null) {
                state("Seni duyamadım");
                main.post(() -> {
                    busy = false;
                    if (after != null) after.run();
                });
            } else {
                ask(null, wav, null, true, after);
            }
        }).start();
    }

    // ------------------------------------------------------------ ana akış

    private void ask(final String text, final byte[] wav, final byte[] jpeg,
                     final boolean speakIt, final Runnable after) {
        busy = true;
        state("Düşünüyorum...");
        new Thread(() -> {
            String out = "";
            String userText = text == null ? "" : text;
            JSONObject plan = null;
            try {
                plan = Gemini.command(app, hist, text, wav, jpeg);
                out = plan.optString("say", "").trim();
                if (wav != null) {
                    String heard = str(plan, "heard");
                    userText = heard;
                    addMessage(true, "🎤 " + (heard.isEmpty() ? "(anlaşılmadı)" : heard));
                }
                String rem = str(plan, "remember");
                if (!rem.isEmpty()) Mem.addNote(app, rem);
                String fg = str(plan, "forget");
                if (!fg.isEmpty()) Mem.forget(app, fg);
                String wi = str(plan, "wish");
                if (!wi.isEmpty()) Mem.addWish(app, wi);
            } catch (Exception e) {
                plan = null;
                out = friendly(e);
            }

            String action = plan == null ? "" : str(plan, "action");
            if ("search".equals(action)) {
                String q = str(plan, "query");
                if (q.isEmpty()) q = userText;
                state("İnternette arıyorum...");
                try {
                    out = Gemini.search(app, q);
                } catch (Exception e) {
                    out = friendly(e);
                }
            } else if ("improve".equals(action)) {
                startImprove(str(plan, "request"));
            } else if (plan != null) {
                String err = Actions.run(app, plan);
                if (err != null) out = err;
            }

            if (out.isEmpty()) out = "Tamam.";
            if (plan != null) {
                hist.add(new String[]{"user", userText.isEmpty() ? "[fotoğraf]" : userText});
                hist.add(new String[]{"model", out.length() > 300 ? out.substring(0, 300) : out});
                while (hist.size() > 12) hist.remove(0);
            }
            reply(out, speakIt, after);
        }).start();
    }

    private void reply(final String out, final boolean speakIt, final Runnable after) {
        addMessage(false, out);
        main.post(() -> {
            final Runnable fin = () -> {
                busy = false;
                state("Hazır");
                if (after != null) after.run();
            };
            if (speakIt) {
                state("Konuşuyorum...");
                speaker.speak(out, fin);
            } else {
                fin.run();
            }
        });
    }

    private static String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("anahtarı girilmemiş")) return "Önce ayarlardan Gemini anahtarını girmelisin.";
        if (m.contains("HTTP 400") || m.contains("HTTP 401") || m.contains("HTTP 403")) {
            return "Gemini isteği kabul etmedi. Anahtarı ya da model adını kontrol et.";
        }
        if (m.contains("HTTP 429")) return "Çok hızlı gittik, kota doldu. Biraz sonra dene.";
        if (m.contains("UnknownHost") || m.contains("timeout") || m.contains("Unable to resolve")
                || m.contains("timed out")) {
            return "İnternete bağlanamadım.";
        }
        return "Bir sorun oldu: " + (m.length() > 100 ? m.substring(0, 100) : m);
    }

    // ------------------------------------------------------------ telefon çalarsa

    /** Ana iş parçacığından çağır. */
    void callTurn(final Runnable after) {
        busy = true;
        state("Arama var");
        speaker.speak("Arayan var. Açayım mı?", () -> new Thread(() -> {
            String ans = "unknown";
            try {
                byte[] wav = Recorder.record(5000, 6000);
                if (wav != null) ans = Gemini.yesNo(app, wav);
            } catch (Exception ignored) { }
            final String a = ans;
            main.post(() -> answerCall(a, after));
        }).start());
    }

    private void answerCall(String a, Runnable after) {
        String msg = null;
        try {
            TelecomManager tm = (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
            boolean ok = app.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!ok) {
                msg = "Arama cevaplama iznim yok.";
            } else if ("yes".equals(a)) {
                tm.acceptRingingCall();
            } else if ("no".equals(a)) {
                if (Build.VERSION.SDK_INT >= 28) {
                    tm.endCall();
                    msg = "Reddettim.";
                } else {
                    msg = "Bu telefonda reddedemiyorum.";
                }
            }
        } catch (Exception e) {
            msg = "Aramayı yönetemedim.";
        }
        if (msg != null) {
            reply(msg, true, after);
        } else {
            busy = false;
            state("Hazır");
            if (after != null) after.run();
        }
    }

    // ------------------------------------------------------------ kendini geliştirme

    void startImprove(final String request) {
        if (improving) return;
        improving = true;
        new Thread(() -> {
            String r;
            try {
                r = Improver.run(app, request);
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                r = "Gelişim olmadı: " + (m.length() > 120 ? m.substring(0, 120) : m);
            }
            improving = false;
            final String res = r;
            addMessage(false, res);
            main.post(() -> {
                if (speakEnabled()) speaker.speak(res, null);
            });
        }).start();
    }
}
