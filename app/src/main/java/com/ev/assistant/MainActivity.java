package com.ev.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private EditText keyBox;
    private EditText modelBox;
    private EditText cmdBox;
    private EditText tokenBox;
    private EditText repoBox;
    private EditText devBox;
    private EditText wishBox;
    private EditText delBox;
    private TextView notesView;
    private TextView devInfo;
    private TextView voiceInfo;
    private TextView voskInfo;
    private boolean downloading = false;
    private TextToSpeech vtts;
    private final List<Voice> voices = new ArrayList<>();
    private int vi = -1;
    private SharedPreferences sp;
    private int pad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("ev", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        pad = (int) (16 * d);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad * 2);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("EV");
        title.setTextSize(32);
        add(root, title);

        keyBox = field(root, "Gemini API anahtarı", sp.getString("key", ""));
        modelBox = field(root, "Model adı", sp.getString("model", "gemini-3.5-flash-lite"));
        btn(root, "Kaydet ve EV'yi başlat", this::startAll);
        btn(root, "1) Üstte gösterme iznini ver", this::openOverlay);
        btn(root, "2) Pil kısıtlamasını kaldır", this::openBattery);
        btn(root, "EV'yi durdur", () -> {
            stopService(new Intent(this, EvService.class));
            toast("EV durdu.");
        });

        label(root, "Test (ses olmadan)");
        cmdBox = field(root, "Örn: saat 9'a alarm kur", "");
        btn(root, "Gönder", this::sendTest);

        label(root, "Çevrimdışı dinleme (bip yok)");
        voskInfo = new TextView(this);
        add(root, voskInfo);
        btn(root, "Modeli indir ve kur (35 MB, Wi-Fi'de yap)", this::downloadModel);
        btn(root, "Çevrimdışı dinlemeyi aç/kapat", this::toggleVosk);

        label(root, "Sesim");
        btn(root, "Sesi değiştir ve dinle", this::cycleVoice);
        voiceInfo = new TextView(this);
        add(root, voiceInfo);

        label(root, "Hafızam");
        notesView = new TextView(this);
        add(root, notesView);
        delBox = field(root, "Silinecek not numarası", "");
        btn(root, "Notu sil", this::deleteNote);
        btn(root, "Tüm hafızayı sil", () -> {
            Mem.clearNotes(this);
            refresh();
        });

        label(root, "Kendini geliştirme");
        TextView info = new TextView(this);
        info.setText("GitHub token'ı sadece bu telefonda saklanır. EV kodu ayrı bir dalda değiştirir, ana sürüme onayınla geçer.");
        add(root, info);
        tokenBox = field(root, "GitHub token", sp.getString("ghToken", ""));
        repoBox = field(root, "Repo (kullanici/repo)", sp.getString("ghRepo", "beratpalaz308-stack/ev-Android"));
        devBox = field(root, "Geliştirme modeli (boşsa yukarıdaki)", sp.getString("devModel", ""));
        wishBox = field(root, "Ne eklensin? (boşsa eksiklerini kendisi seçer)", "");
        btn(root, "Kendini geliştir", this::improveNow);
        devInfo = new TextView(this);
        add(root, devInfo);
        btn(root, "Son denemeyi onayla (ana sürüm yap)", this::approve);

        vtts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            try {
                for (Voice v : vtts.getVoices()) {
                    if ("tr".equals(v.getLocale().getLanguage())) voices.add(v);
                }
                java.util.Collections.sort(voices, (a, c) -> a.getName().compareTo(c.getName()));
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

    private void add(LinearLayout root, View v) {
        root.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private EditText field(LinearLayout root, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setText(value);
        add(root, e);
        return e;
    }

    private void label(LinearLayout root, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setPadding(0, pad * 2, 0, 0);
        add(root, t);
    }

    private void btn(LinearLayout root, String text, final Runnable r) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(v -> r.run());
        add(root, bt);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void save() {
        String model = modelBox.getText().toString().trim();
        if (model.isEmpty()) model = "gemini-3.5-flash-lite";
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

    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip";

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
        toast("EV'yi durdurup yeniden başlat.");
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
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
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
                result = "Kuruldu. EV'yi durdurup yeniden başlat.";
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

    @Override
    protected void onDestroy() {
        if (vtts != null) {
            vtts.stop();
            vtts.shutdown();
        }
        super.onDestroy();
    }

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

    private String[] perms() {
        ArrayList<String> l = new ArrayList<>();
        l.add(Manifest.permission.RECORD_AUDIO);
        l.add(Manifest.permission.CALL_PHONE);
        l.add(Manifest.permission.ANSWER_PHONE_CALLS);
        l.add(Manifest.permission.READ_PHONE_STATE);
        l.add(Manifest.permission.READ_CONTACTS);
        l.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
        return l.toArray(new String[0]);
    }

    private void startAll() {
        save();
        if (sp.getString("key", "").isEmpty()) {
            toast("Önce Gemini API anahtarını yapıştır.");
            return;
        }
        ArrayList<String> need = new ArrayList<>();
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (need.isEmpty()) {
            launchService();
        } else {
            requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchService();
        } else {
            toast("Mikrofon izni şart.");
        }
    }

    private void launchService() {
        try {
            startForegroundService(new Intent(this, EvService.class));
            toast("EV çalışıyor. \"Hey EV\" de.");
        } catch (Exception e) {
            toast("Başlatılamadı: " + e.getMessage());
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

    private void sendTest() {
        save();
        String c = cmdBox.getText().toString().trim();
        if (c.isEmpty()) return;
        try {
            Intent i = new Intent(this, EvService.class);
            i.putExtra("cmd", c);
            startForegroundService(i);
        } catch (Exception e) {
            toast("Gönderilemedi: " + e.getMessage());
        }
    }

    private void improveNow() {
        save();
        if (sp.getString("ghToken", "").isEmpty() || sp.getString("ghRepo", "").isEmpty()) {
            toast("Önce GitHub token ve repo adını gir.");
            return;
        }
        try {
            Intent i = new Intent(this, EvService.class);
            i.putExtra("improve", wishBox.getText().toString().trim());
            startForegroundService(i);
            toast("EV geliştirmeye başladı, sonucu sesli söyleyecek.");
        } catch (Exception e) {
            toast("Başlatılamadı: " + e.getMessage());
        }
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
