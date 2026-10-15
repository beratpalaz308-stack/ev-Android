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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private EditText keyBox;
    private EditText modelBox;
    private EditText cmdBox;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("ev", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("EV");
        title.setTextSize(32);
        add(root, title);

        TextView info = new TextView(this);
        info.setText("Sırayla: anahtarı yapıştır, başlat, izinleri ver, sonra iki izin düğmesine bas. Ardından \"hey ev\" de.");
        info.setPadding(0, 0, 0, pad);
        add(root, info);

        keyBox = new EditText(this);
        keyBox.setHint("Gemini API anahtarı");
        keyBox.setSingleLine(true);
        keyBox.setText(sp.getString("key", ""));
        add(root, keyBox);

        modelBox = new EditText(this);
        modelBox.setHint("Model adı");
        modelBox.setSingleLine(true);
        modelBox.setText(sp.getString("model", "gemini-3.5-flash-lite"));
        add(root, modelBox);

        btn(root, "Kaydet ve EV'yi başlat", this::startAll);
        btn(root, "1) Üstte gösterme iznini ver", this::openOverlay);
        btn(root, "2) Pil kısıtlamasını kaldır", this::openBattery);
        btn(root, "EV'yi durdur", () -> {
            stopService(new Intent(this, EvService.class));
            toast("EV durdu.");
        });

        TextView t2 = new TextView(this);
        t2.setText("Test (ses olmadan):");
        t2.setPadding(0, pad, 0, 0);
        add(root, t2);

        cmdBox = new EditText(this);
        cmdBox.setHint("Örn: saat 9'a alarm kur");
        add(root, cmdBox);
        btn(root, "Gönder", this::sendTest);

        setContentView(sv);
    }

    private void add(LinearLayout root, View v) {
        root.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
                .apply();
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
}
