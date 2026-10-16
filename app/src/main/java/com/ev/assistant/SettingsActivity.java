package com.ev.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SettingsActivity extends Activity {
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip";

    private SharedPreferences sp;
    private LinearLayout root;
    private EditText keyBox;
    private EditText modelBox;
    private EditText tokenBox;
    private EditText repoBox;
    private EditText devBox;
    private EditText wishBox;
    private EditText delBox;
    private TextView notesView;
    private TextView devInfo;
    private TextView voiceInfo;
    private TextView voskInfo;
    private TextToSpeech vtts;
    private final List<Voice> voices = new ArrayList<>();
    private int vi = -1;
    private boolean downloading = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("ev", MODE_PRIVATE);
        int pad = Ui.dp(this, 16);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 40), pad, pad * 2);
        sv.addView(root);

        add(Ui.text(this, "Ayarlar", 26, Ui.TEXT), 0);

        section("Bağlantı");
        keyBox = field("Gemini API anahtarı", sp.getString("key", ""));
        modelBox = field("Model adı", sp.getString("model", Gemini.DEFAULT_MODEL));
        note("Anahtarı buradan alırsın: aistudio.google.com/apikey");

        section("İzinler");
        btn("İzinleri ver (mikrofon, telefon, rehber, mesaj)", this::askPerms);
        btn("Üstte gösterme iznini ver", this::openOverlay);
        btn("Pil kısıtlamasını kaldır", this::openBattery);

        section("Arka planda dinleme");
        btn("Başlat", this::startBg);
        btn("Durdur", this::stopBg);

        section("Çevrimdışı \"hey ev\" (bipsiz)");
        voskInfo = Ui.text(this, "", 14, Ui.MUTED);
        add(voskInfo, 8);
        btn("Modeli indir ve kur (35 MB, Wi-Fi'de yap)", this::downloadModel);
        btn("Çevrimdışı dinlemeyi aç/kapat", this::toggleVosk);

        section("Sesim");
        voiceInfo = Ui.text(this, "", 14, Ui.MUTED);
        add(voiceInfo, 8);
        btn("Sesi değiştir ve dinle", this::cycleVoice);

        section("Hafızam");
        notesView = Ui.text(this, "", 15, Ui.TEXT);
        add(notesView, 8);
        delBox = field("Silinecek not numarası", "");
        btn("Notu sil", this::deleteNote);
        btn("Tüm hafızayı sil", () -> {
            Mem.clearNotes(this);
            refresh();
        });

        section("Kendini geliştirme");
        note("GitHub token'ı sadece bu telefonda saklanır. EV kodu ayrı bir dalda değiştirir, ana sürüme onayınla geçer.");
        tokenBox = field("GitHub token", sp.getString("ghToken", ""));
        repoBox = field("Repo (kullanici/repo)", sp.getString("ghRepo", "beratpalaz308-stack/ev-Android"));
        devBox = field("Geliştirme modeli (boşsa yukarıdaki)", sp.getString("devModel", ""));
        wishBox = field("Ne eklensin? (boşsa eksiklerini kendisi seçer)", "");
        btn("Kendini geliştir", this::improveNow);
        devInfo = Ui.text(this, "", 14, Ui.MUTED);
        add(devInfo, 8);
        btn("Son denemeyi onayla (ana sürüm yap)", this::approve);

        vtts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            try {
                for (Voice v : vtts.getVoices()) {
                    if ("tr".equals(v.getLocale().getLanguage())) voices.add(v);
                }
                Collections.sort(voices, (a, c) -> a.getName().compareTo(c.getName()));
            } catch (Exception ignored) { }
            runOnUiThread(() -> voiceInfo.setText(voices.isEmpty()
                    ? "Türkçe ses bulunamadı."
                    : voices.size() + " Türkçe ses var. Düğmeye bas, kadın sesini kulağınla seç."));
        });

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (!downloading) refreshVosk();
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (vtts != null) {
            vtts.stop();
            vtts.shutdown();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- arayüz yardımcıları

    private void add(View v, int topDp) {
        root.addView(v, Ui.matchWrap(this, topDp));
    }

    private void section(String s) {
        TextView t = Ui.text(this, s, 20, Ui.ACCENT);
        add(t, 28);
    }

    private void note(String s) {
        add(Ui.text(this, s, 13, Ui.MUTED), 6);
    }

    private EditText field(String hint, String value) {
        EditText e = Ui.field(this, hint, value);
        add(e, 8);
        return e;
    }

    private void btn(String text, final Runnable r) {
        android.widget.Button b = Ui.button(this, text, Ui.SURFACE);
        b.setOnClickListener(v -> r.run());
        add(b, 8);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void save() {
        String model = modelBox.getText().toString().trim();
        if (model.isEmpty()) model = Gemini.DEFAULT_MODEL;
        sp.edit()
                .putString("key", keyBox.getText().toString().trim())
                .putString("model", model)
                .putString("ghToken", tokenBox.getText().toString().trim())
                .putString("ghRepo", repoBox.getText().toString().trim())
                .putString("devModel", devBox.getText().toString().trim())
                .apply();
    }

    private void refresh() {
        List<String> n = Mem.notes(this);
        StringBuilder sb = new StringBuilder();
        if (n.isEmpty()) sb.append("(henüz bir şey öğrenmedi)");
        for (int i = 0; i < n.size(); i++) {
            sb.append(i + 1).append(". ").append(n.get(i)).append("\n");
        }
        List<String> w = Mem.wishes(this);
        if (!w.isEmpty()) {
            sb.append("\nEksik bulduğu: ");
            for (int i = 0; i < w.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(w.get(i));
            }
        }
        notesView.setText(sb.toString());
        String br = sp.getString("lastBranch", "");
        devInfo.setText(br.isEmpty()
                ? "Bekleyen deneme yok."
                : "Son deneme: " + sp.getString("lastSummary", "") + " (" + br + ")");
    }

    // ---------------------------------------------------------------- izinler ve servis

    private void askPerms() {
        ArrayList<String> need = new ArrayList<>();
        for (String p : Ui.perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (need.isEmpty()) {
            toast("Tüm izinler zaten verilmiş.");
        } else {
            requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    private void openOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Ayarlar açılamadı.");
        }
    }

    private void openBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Ayarlar açılamadı.");
        }
    }

    private void startBg() {
        save();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Önce mikrofon iznini ver.");
            return;
        }
        sp.edit().putBoolean("bg", true).apply();
        try {
            startForegroundService(new Intent(this, EvService.class));
            toast("EV arka planda dinliyor.");
        } catch (Exception e) {
            toast("Başlatılamadı: " + e.getMessage());
        }
    }

    private void stopBg() {
        sp.edit().putBoolean("bg", false).apply();
        stopService(new Intent(this, EvService.class));
        toast("Arka planda dinleme durdu.");
    }

    // ---------------------------------------------------------------- çevrimdışı dinleme

    private void refreshVosk() {
        boolean has = Wake.modelDir(this) != null;
        voskInfo.setText("Model: " + (has ? "kurulu" : "yok")
                + ", çevrimdışı dinleme: " + (sp.getBoolean("vosk", false) ? "açık" : "kapalı"));
    }

    private void toggleVosk() {
        if (Wake.modelDir(this) == null) {
            toast("Önce modeli indir.");
            return;
        }
        sp.edit().putBoolean("vosk", !sp.getBoolean("vosk", false)).apply();
        refreshVosk();
        toast("Değişiklik için arka planda dinlemeyi durdurup başlat.");
    }

    private void downloadModel() {
        if (downloading) return;
        downloading = true;
        voskInfo.setText("İndiriliyor...");
        new Thread(() -> {
            String result;
            try {
                File base = new File(getFilesDir(), "vosk");
                deleteRec(base);
                base.mkdirs();
                File zip = new File(getFilesDir(), "model.zip");
                HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(zip)) {
                    byte[] buf = new byte[32768];
                    int n;
                    long done = 0;
                    long last = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done - last > 1024 * 1024) {
                            last = done;
                            final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            runOnUiThread(() -> voskInfo.setText("İndiriliyor: %" + pct));
                        }
                    }
                }
                runOnUiThread(() -> voskInfo.setText("Açılıyor..."));
                unzip(zip, base);
                zip.delete();
                if (Wake.modelDir(this) == null) throw new Exception("model klasörü bulunamadı");
                sp.edit().putBoolean("vosk", true).apply();
                result = "Kuruldu. Arka planda dinlemeyi durdurup başlat.";
            } catch (Exception e) {
                result = "Olmadı: " + e.getMessage();
            }
            downloading = false;
            final String r = result;
            runOnUiThread(() -> {
                voskInfo.setText(r);
                toast(r);
            });
        }).start();
    }

    private void unzip(File zip, File dest) throws Exception {
        String base = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new Exception("zip yolu geçersiz");
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    // ---------------------------------------------------------------- ses

    private void cycleVoice() {
        if (vtts == null || voices.isEmpty()) {
            toast("Türkçe ses henüz hazır değil.");
            return;
        }
        vi = (vi + 1) % voices.size();
        Voice v = voices.get(vi);
        try {
            vtts.setVoice(v);
            vtts.speak("Merhaba, ben EV. Beni böyle mi duymak istersin?", TextToSpeech.QUEUE_FLUSH, null, "demo");
            sp.edit().putString("voice", v.getName()).apply();
            voiceInfo.setText("Seçili ses " + (vi + 1) + "/" + voices.size() + ": " + v.getName());
        } catch (Exception e) {
            toast("Bu sesi açamadım.");
        }
    }

    // ---------------------------------------------------------------- hafıza

    private void deleteNote() {
        try {
            int idx = Integer.parseInt(delBox.getText().toString().trim());
            Mem.deleteAt(this, idx - 1);
            delBox.setText("");
            refresh();
        } catch (Exception e) {
            toast("Bir numara yaz.");
        }
    }

    // ---------------------------------------------------------------- kendini geliştirme

    private void improveNow() {
        save();
        if (sp.getString("ghToken", "").isEmpty() || sp.getString("ghRepo", "").isEmpty()) {
            toast("Önce GitHub token ve repo adını gir.");
            return;
        }
        EvCore.get(this).startImprove(wishBox.getText().toString().trim());
        toast("EV geliştirmeye başladı. Sonucu sohbette yazar ve söyler.");
    }

    private void approve() {
        save();
        final String branch = sp.getString("lastBranch", "");
        final String token = sp.getString("ghToken", "");
        final String repo = sp.getString("ghRepo", "");
        if (branch.isEmpty()) {
            toast("Onaylanacak bir deneme yok.");
            return;
        }
        if (token.isEmpty() || repo.isEmpty()) {
            toast("GitHub token ve repo adı lazım.");
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                String body = new JSONObject()
                        .put("base", "main")
                        .put("head", branch)
                        .put("commit_message", "EV: onaylandı " + branch)
                        .toString();
                Gh.http("POST", "https://api.github.com/repos/" + repo + "/merges", Gh.headers(token), body);
                sp.edit().remove("lastBranch").remove("lastSummary").apply();
                msg = "Ana sürüme eklendi. Yeni derleme başlıyor.";
            } catch (Exception e) {
                msg = "Olmadı: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                toast(m);
                refresh();
            });
        }).start();
    }
}
