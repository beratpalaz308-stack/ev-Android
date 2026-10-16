package com.ev.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Mem {
    private Mem() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ev", Context.MODE_PRIVATE);
    }

    private static synchronized List<String> load(Context c, String k) {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) { }
        return l;
    }

    private static synchronized void save(Context c, String k, List<String> l) {
        sp(c).edit().putString(k, new JSONArray(l).toString()).apply();
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() < 3) return null;
        if (t.length() > max) t = t.substring(0, max);
        return t;
    }

    private static boolean sensitive(String t) {
        String low = t.toLowerCase(new Locale("tr", "TR"));
        return t.matches(".*\\d{6,}.*")
                || low.contains("şifre") || low.contains("parola") || low.contains("password")
                || low.contains("iban") || low.contains("kart no");
    }

    static List<String> notes(Context c) {
        return load(c, "notes");
    }

    static List<String> wishes(Context c) {
        return load(c, "wishes");
    }

    static synchronized void addNote(Context c, String n) {
        String t = clean(n, 200);
        if (t == null || sensitive(t)) return;
        List<String> l = load(c, "notes");
        for (String x : l) {
            if (x.equalsIgnoreCase(t)) return;
        }
        l.add(t);
        while (l.size() > 60) l.remove(0);
        save(c, "notes", l);
    }

    static synchronized int forget(Context c, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(new Locale("tr", "TR"));
        if (kw.length() < 2) return 0;
        List<String> l = load(c, "notes");
        List<String> keep = new ArrayList<>();
        for (String x : l) {
            if (!x.toLowerCase(new Locale("tr", "TR")).contains(kw)) keep.add(x);
        }
        int removed = l.size() - keep.size();
        if (removed > 0) save(c, "notes", keep);
        return removed;
    }

    static synchronized void deleteAt(Context c, int idx) {
        List<String> l = load(c, "notes");
        if (idx >= 0 && idx < l.size()) {
            l.remove(idx);
            save(c, "notes", l);
        }
    }

    static synchronized void clearNotes(Context c) {
        save(c, "notes", new ArrayList<String>());
    }

    static synchronized void addWish(Context c, String w) {
        String t = clean(w, 120);
        if (t == null) return;
        List<String> l = load(c, "wishes");
        for (String x : l) {
            if (x.equalsIgnoreCase(t)) return;
        }
        l.add(t);
        while (l.size() > 20) l.remove(0);
        save(c, "wishes", l);
    }

    static synchronized void clearWishes(Context c) {
        save(c, "wishes", new ArrayList<String>());
    }
}
