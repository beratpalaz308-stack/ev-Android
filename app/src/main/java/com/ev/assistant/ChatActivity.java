package com.ev.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends Activity implements EvCore.Listener {
    private static final int REQ_PHOTO = 7;
    private static boolean askedKey = false;

    private EvCore core;
    private SharedPreferences sp;
    private LinearLayout chat;
    private ScrollView scroll;
    private TextView status;
    private TextView speakBtn;
    private EditText input;
    private Uri photoUri;
    private boolean askedPerms = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        core = EvCore.get(this);
        sp = getSharedPreferences("ev", MODE_PRIVATE);
        int p = Ui.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(p, Ui.dp(this, 32), p, Ui.dp(this, 6));
        TextView title = Ui.text(this, "EV", 26, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        status = Ui.text(this, "Hazır", 13, Ui.ACCENT);
        status.setPadding(Ui.dp(this, 12), 0, 0, 0);
        View spacer = new View(this);
        speakBtn = circle(speakGlyph(), Ui.SURFACE, this::toggleSpeak);
        TextView gear = circle("⚙", Ui.SURFACE, () -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(title);
        bar.addView(status);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(speakBtn);
        bar.addView(gear);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(p, p / 2, p, p);
        scroll.addView(chat);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p / 2, p / 2, p / 2, p);
        TextView cam = circle("📷", Ui.SURFACE, this::takePhoto);
        input = Ui.field(this, "EV'ye yaz...", "");
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            sendTyped();
            return true;
        });
        TextView send = circle("➤", Ui.VIOLET, this::sendTyped);
        TextView mic = circle("🎤", Ui.ACCENT, this::pushToTalk);
        row.addView(cam);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(send);
        row.addView(mic);
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        if (core.snapshot().isEmpty()) {
            core.addMessage(false, "Selam, ben EV. Yaz, 🎤'ya dokun ya da 📷 ile bana bir şey göster.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        chat.removeAllViews();
        for (Object[] m : core.snapshot()) addBubble((Boolean) m[0], (String) m[1]);
        status.setText(core.lastState());
        core.addListener(this);
        ensureSetup();
    }

    @Override
    protected void onPause() {
        core.removeListener(this);
        super.onPause();
    }

    // ---------------------------------------------------------------- EvCore.Listener

    @Override
    public void onMessage(boolean user, String text) {
        addBubble(user, text);
    }

    @Override
    public void onState(String s) {
        status.setText(s);
    }

    // ---------------------------------------------------------------- arayüz parçaları

    private TextView circle(String glyph, int bg, final Runnable r) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Ui.TEXT);
        t.setBackground(Ui.shape(this, bg, 24));
        int s = Ui.dp(this, 48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void addBubble(boolean user, String text) {
        TextView t = Ui.text(this, text, 16, Ui.TEXT);
        t.setBackground(Ui.shape(this, user ? Ui.VIOLET : Ui.BUBBLE_EV, 18));
        int px = Ui.dp(this, 12);
        t.setPadding(px + 4, px, px + 4, px);
        t.setMaxWidth(Ui.dp(this, 290));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(this, 8);
        chat.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private String speakGlyph() {
        return sp.getBoolean("speak", true) ? "🔊" : "🔇";
    }

    private void toggleSpeak() {
        sp.edit().putBoolean("speak", !sp.getBoolean("speak", true)).apply();
        speakBtn.setText(speakGlyph());
        toast(sp.getBoolean("speak", true) ? "Sesli cevap açık" : "Sesli cevap kapalı");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- eylemler

    private boolean ready() {
        if (sp.getString("key", "").isEmpty()) {
            toast("Önce ayarlardan Gemini anahtarını gir.");
            startActivity(new Intent(this, SettingsActivity.class));
            return false;
        }
        return true;
    }

    private void sendTyped() {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || !ready()) return;
        if (core.isBusy()) {
            toast("EV meşgul, bir saniye.");
            return;
        }
        input.setText("");
        core.addMessage(true, t);
        core.askTyped(t, null);
    }

    private void pushToTalk() {
        if (!ready()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Mikrofon izni gerekli.");
            requestPermissions(Ui.perms(), 1);
            return;
        }
        if (core.isBusy()) {
            toast("EV meşgul, bir saniye.");
            return;
        }
        try {
            startForegroundService(new Intent(this, EvService.class).setAction(EvService.ACT_LISTEN));
        } catch (Exception e) {
            toast("Başlatılamadı: " + e.getMessage());
        }
    }

    private void takePhoto() {
        if (!ready()) return;
        if (core.isBusy()) {
            toast("EV meşgul, bir saniye.");
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "ev_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            photoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (photoUri == null) {
                toast("Kamera için yer açamadım.");
                return;
            }
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            startActivityForResult(i, REQ_PHOTO);
        } catch (Exception e) {
            toast("Kamera açılamadı.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PHOTO || photoUri == null) return;
        final Uri u = photoUri;
        photoUri = null;
        if (res != RESULT_OK) {
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            return;
        }
        final String q = input.getText().toString().trim();
        input.setText("");
        core.addMessage(true, "📷 " + (q.isEmpty() ? "Bunu göster" : q));
        new Thread(() -> {
            byte[] jpeg = null;
            try {
                jpeg = Img.fromUri(this, u);
            } catch (Exception e) {
                core.addMessage(false, "Fotoğrafı açamadım.");
            }
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            if (jpeg != null) {
                final byte[] j = jpeg;
                runOnUiThread(() -> core.askTyped(q, j));
            }
        }).start();
    }

    // ---------------------------------------------------------------- kurulum

    private void ensureSetup() {
        if (sp.getString("key", "").isEmpty()) {
            if (!askedKey) {
                askedKey = true;
                toast("Önce Gemini anahtarını gir.");
                startActivity(new Intent(this, SettingsActivity.class));
            }
            return;
        }
        List<String> need = new ArrayList<>();
        for (String p : Ui.perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty() && !askedPerms) {
            askedPerms = true;
            requestPermissions(need.toArray(new String[0]), 1);
            return;
        }
        startBackground();
    }

    private void startBackground() {
        if (!sp.getBoolean("bg", true)) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            startForegroundService(new Intent(this, EvService.class));
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        startBackground();
    }
}
