package com.ev.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Telefonun içinde, internetsiz ve bipsiz "hey ev" dinleyicisi. */
final class Wake {
    interface Callback {
        void onWake(String heard);

        void onFail();
    }

    private static final Pattern TWO = Pattern.compile("\\b(hey|hay|hei|he)\\s+(ev|eve|ef|if)\\b");

    private final Context ctx;
    private final Callback cb;
    private Model model;
    private SpeechService svc;
    private boolean twoWord = true;
    private String grammar = "[\"hey ev\", \"ev\", \"[unk]\"]";
    private volatile boolean active = false;

    Wake(Context c, Callback cb) {
        this.ctx = c;
        this.cb = cb;
    }

    static File modelDir(Context c) {
        File base = new File(c.getFilesDir(), "vosk");
        File[] kids = base.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.isDirectory() && new File(f, "am").exists()) return f;
            }
        }
        return null;
    }

    boolean start() {
        try {
            if (model == null) {
                File dir = modelDir(ctx);
                if (dir == null) return false;
                Model m = new Model(dir.getAbsolutePath());
                if (!prepare(dir)) {
                    m.close();
                    return false;
                }
                model = m;
            }
            Recognizer rec = new Recognizer(model, 16000.0f, grammar);
            svc = new SpeechService(rec, 16000.0f);
            active = true;
            svc.startListening(listener);
            return true;
        } catch (Throwable t) {
            active = false;
            stop();
            return false;
        }
    }

    void stop() {
        active = false;
        SpeechService s = svc;
        svc = null;
        if (s != null) {
            try { s.cancel(); } catch (Throwable ignored) { }
            try { s.shutdown(); } catch (Throwable ignored) { }
        }
    }

    void release() {
        stop();
        Model m = model;
        model = null;
        if (m != null) {
            try { m.close(); } catch (Throwable ignored) { }
        }
    }

    /** Modelin sözlüğüne bakıp sadece bilinen kelimelerle dilbilgisi kurar. */
    private boolean prepare(File dir) {
        Set<String> words = new HashSet<>();
        File f = new File(dir, "graph/words.txt");
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    words.add(sp > 0 ? line.substring(0, sp) : line.trim());
                }
            } catch (Exception ignored) { }
        }
        List<String> phrases = new ArrayList<>();
        if (words.isEmpty()) {
            twoWord = false;
            phrases.add("hey ev");
            phrases.add("ev");
        } else {
            if (!words.contains("ev")) return false;
            boolean any = false;
            for (String h : new String[]{"hey", "hay"}) {
                if (words.contains(h)) {
                    phrases.add(h + " ev");
                    any = true;
                }
            }
            twoWord = any;
            if (!any) phrases.add("ev");
        }
        phrases.add("[unk]");
        JSONArray a = new JSONArray();
        for (String p : phrases) a.put(p);
        grammar = a.toString();
        return true;
    }

    private boolean matches(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.isEmpty()) return false;
        if (TWO.matcher(s).find()) return true;
        return !twoWord && s.equals("ev");
    }

    private void check(String json, String key) {
        if (!active) return;
        try {
            String t = new JSONObject(json).optString(key, "");
            if (matches(t)) {
                active = false;
                cb.onWake(t);
            }
        } catch (Exception ignored) { }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
            check(hypothesis, "partial");
        }

        @Override
        public void onResult(String hypothesis) {
            check(hypothesis, "text");
        }

        @Override
        public void onFinalResult(String hypothesis) { }

        @Override
        public void onError(Exception e) {
            if (active) {
                active = false;
                cb.onFail();
            }
        }

        @Override
        public void onTimeout() { }
    };
}
