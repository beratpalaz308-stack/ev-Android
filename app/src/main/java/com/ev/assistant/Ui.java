package com.ev.assistant;

import android.Manifest;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** Renkler ve küçük arayüz yardımcıları (koyu tema). */
final class Ui {
    static final int BG = 0xFF0B0F1A;
    static final int SURFACE = 0xFF151B2E;
    static final int VIOLET = 0xFF6D4AFF;
    static final int ACCENT = 0xFF19D3C5;
    static final int TEXT = 0xFFEAF0FF;
    static final int MUTED = 0xFF8A93B2;
    static final int BUBBLE_EV = 0xFF1B2440;

    private Ui() { }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable shape(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Context c, String s, int bg) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(shape(c, bg, 14));
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        return b;
    }

    static EditText field(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setText(value);
        e.setSingleLine(true);
        e.setBackground(shape(c, SURFACE, 12));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return e;
    }

    static LinearLayout.LayoutParams matchWrap(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static String[] perms() {
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
}
