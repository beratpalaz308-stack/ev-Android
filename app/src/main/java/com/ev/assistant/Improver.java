package com.ev.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

final class Improver {
    private Improver() { }

    private static final String PATH = "app/src/main/java/com/ev/assistant/EvService.java";

    private static final String DEV_SYSTEM =
            "You modify ONE Android Java file (EvService.java) of a voice assistant app called EV. "
            + "Return ONLY a JSON object: {\"summary\": string, \"edits\": [{\"old\": string, \"new\": string}]}. "
            + "summary is Turkish, at most 15 words, describing what changed. "
            + "Each edit replaces the text 'old' with 'new'. 'old' must be copied EXACTLY (whitespace included) from the source and must occur exactly once in it. "
            + "Keep the change small and make sure it compiles: Java 17, Android SDK 34, no new libraries, no new permissions "
            + "(available: internet, microphone, phone calls, answer calls, contacts, SMS, alarm). "
            + "If you use a new class, add its import with another edit. "
            + "Never remove or break startForeground, the speech recognition loop, speak(), or the existing actions. "
            + "runAction() runs on the main thread and returns a String that, when not null, REPLACES what EV says (use it for errors); "
            + "for network calls start a new Thread, then call main.post(() -> speak(text)) and return null. "
            + "Prefer free APIs that need no key. To add a new action: add a case in runAction(), and add it to the action list and rules inside systemPrompt(). "
            + "Do not touch any other file.";

    static String run(Context ctx, String request) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences("ev", Context.MODE_PRIVATE);
        String token = sp.getString("ghToken", "").trim();
        String repo = sp.getString("ghRepo", "").trim();
        String key = sp.getString("key", "").trim();
        String model = sp.getString("devModel", "").trim();
        if (model.isEmpty()) model = sp.getString("model", "gemini-3.5-flash-lite").trim();
        if (model.isEmpty()) model = "gemini-3.5-flash-lite";
        if (token.isEmpty() || repo.isEmpty()) {
            return "Gelişmem için uygulamaya GitHub token'ını ve repo adını girmen lazım.";
        }
        if (key.isEmpty()) return "API anahtarı yok.";

        String ask = request == null ? "" : request.trim();
        List<String> w = Mem.wishes(ctx);
        StringBuilder wishes = new StringBuilder();
        for (String x : w) wishes.append("- ").append(x).append("\n");
        if (ask.isEmpty() && wishes.length() == 0) {
            return "Şu an eksik bir yanım yok. Ne eklememi istediğini söyle.";
        }

        String api = "https://api.github.com/repos/" + repo;
        String[][] gh = Gh.headers(token);

        String mainSha = new JSONObject(Gh.http("GET", api + "/git/ref/heads/main", gh, null))
                .getJSONObject("object").getString("sha");
        JSONObject file = new JSONObject(Gh.http("GET", api + "/contents/" + PATH + "?ref=main", gh, null));
        String fileSha = file.getString("sha");
        String src = new String(Base64.decode(file.getString("content"), Base64.DEFAULT), "UTF-8");

        String user = "Request from the user: " + (ask.isEmpty() ? "(none, pick the most useful missing ability)" : ask)
                + "\nMissing abilities EV noticed:\n" + (wishes.length() == 0 ? "(none)\n" : wishes.toString())
                + "\nSOURCE of EvService.java:\n" + src;

        String text = gemini(key, model, DEV_SYSTEM, user);
        JSONObject plan = new JSONObject(text);
        String summary = plan.optString("summary", "küçük bir geliştirme").trim();
        JSONArray edits = plan.getJSONArray("edits");
        if (edits.length() == 0) return "Değiştirecek bir şey bulamadım.";

        String out = src;
        for (int i = 0; i < edits.length(); i++) {
            JSONObject e = edits.getJSONObject(i);
            String o = e.getString("old");
            String n = e.getString("new");
            if (o.isEmpty()) throw new Exception("boş eski metin");
            int at = out.indexOf(o);
            if (at < 0) throw new Exception("eski metin bulunamadı");
            if (out.indexOf(o, at + 1) >= 0) throw new Exception("eski metin birden fazla yerde");
            out = out.substring(0, at) + n + out.substring(at + o.length());
        }

        if (!out.contains("public class EvService extends Service")
                || !out.contains("startForeground")
                || !out.contains("onDestroy")) {
            throw new Exception("kod bütünlüğü bozuldu");
        }
        if (count(out, '{') != count(out, '}')) throw new Exception("süslü parantezler tutmuyor");
        if (out.length() < src.length() * 0.7 || out.length() > src.length() * 1.6) {
            throw new Exception("değişiklik fazla büyük");
        }

        String branch = "ev-yeni-" + (System.currentTimeMillis() / 1000);
        Gh.http("POST", api + "/git/refs", gh,
                new JSONObject().put("ref", "refs/heads/" + branch).put("sha", mainSha).toString());
        JSONObject put = new JSONObject()
                .put("message", "EV: " + summary)
                .put("content", Base64.encodeToString(out.getBytes("UTF-8"), Base64.NO_WRAP))
                .put("sha", fileSha)
                .put("branch", branch);
        Gh.http("PUT", api + "/contents/" + PATH, gh, put.toString());

        sp.edit().putString("lastBranch", branch).putString("lastSummary", summary).apply();
        Mem.clearWishes(ctx);
        return "Yeni sürüm hazırlandı: " + summary + ". GitHub derlerken beş dakika bekle, sonra denemek için indir.";
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) n++;
        }
        return n;
    }

    private static String gemini(String key, String model, String system, String user) throws Exception {
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", system))));
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", user)))));
        body.put("generationConfig", new JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2));
        String resp = Gh.http("POST",
                "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
                new String[][]{{"x-goog-api-key", key}}, body.toString());
        JSONArray parts = new JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (!part.optBoolean("thought", false)) sb.append(part.optString("text", ""));
        }
        String t = sb.toString().trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(json)?", "").replaceFirst("```\\s*$", "").trim();
        }
        return t;
    }
}
