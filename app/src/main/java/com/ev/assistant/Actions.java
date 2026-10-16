package com.ev.assistant;

import android.Manifest;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * EV'nin telefonda yapabildiği eylemler. run() arka plan iş parçacığında çalışır.
 * null döndürürse sorun yok; metin döndürürse EV normal cevap yerine onu söyler.
 */
final class Actions {
    private Actions() { }

    private static final Locale TR = new Locale("tr", "TR");

    /** Gemini'nin seçebildiği eylem adları ('|' ile ayrılır). */
    static final String LIST = "alarm|timer|call|sms|music|media|torch|open_app|chords";

    /** Gemini'ye eylemlerin nasıl doldurulacağını anlatan kurallar. */
    static final String RULES =
            "alarm için hour (0-23) ve minute ver; 'yarım saat sonra' gibi göreli sürelerde şu anki saatten hesapla. "
            + "timer için minutes ver (geri sayım). "
            + "call için name veya number ver. sms için name veya number ve text ver. "
            + "music için query ver (şarkı veya sanatçı). media için value ver: play, pause, next veya previous. "
            + "torch için value ver: on veya off. open_app için app ver (uygulama adı). "
            + "chords için query ver (şarkı adı ve sanatçı); şarkıyı bilmiyorsan query'yi boş bırak ve say ile hangi şarkı olduğunu sor. ";

    static String run(Context c, JSONObject p) {
        String a = str(p, "action");
        try {
            switch (a) {
                case "alarm": {
                    int h = p.optInt("hour", -1);
                    int m = p.optInt("minute", 0);
                    if (h < 0 || h > 23 || m < 0 || m > 59) return "Saati anlayamadım.";
                    String label = str(p, "label");
                    if (label.isEmpty()) label = "EV";
                    return launch(c, new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, h)
                            .putExtra(AlarmClock.EXTRA_MINUTES, m)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true));
                }
                case "timer": {
                    int mins = p.optInt("minutes", 0);
                    if (mins <= 0) return "Kaç dakika olduğunu anlayamadım.";
                    return launch(c, new Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, "EV")
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true));
                }
                case "call": {
                    if (!has(c, Manifest.permission.CALL_PHONE)) return "Arama izni verilmemiş.";
                    String num = resolveNumber(c, p);
                    if (num == null) return "Rehberde bulamadım.";
                    return launch(c, new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))));
                }
                case "sms": {
                    String num = resolveNumber(c, p);
                    String text = str(p, "text");
                    if (num == null) return "Kişiyi bulamadım.";
                    if (text.isEmpty()) return "Mesajın ne olacağını anlayamadım.";
                    if (!has(c, Manifest.permission.SEND_SMS)) {
                        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)));
                        i.putExtra("sms_body", text);
                        String r = launch(c, i);
                        return r != null ? r : "Mesaj ekranını açtım, gönder'e bas.";
                    }
                    SmsManager sm = (Build.VERSION.SDK_INT >= 31)
                            ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
                    ArrayList<String> parts = sm.divideMessage(text);
                    sm.sendMultipartTextMessage(num, null, parts, null, null);
                    return null;
                }
                case "music": {
                    String q = str(p, "query");
                    if (q.isEmpty()) return "Ne çalayım?";
                    Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
                    i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                    i.putExtra(SearchManager.QUERY, q);
                    String r = launch(c, i);
                    if (r != null) {
                        r = launch(c, new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))));
                    }
                    return r;
                }
                case "media": {
                    String v = str(p, "value").toLowerCase(Locale.ROOT);
                    int key;
                    if (v.startsWith("pause")) key = KeyEvent.KEYCODE_MEDIA_PAUSE;
                    else if (v.startsWith("play")) key = KeyEvent.KEYCODE_MEDIA_PLAY;
                    else if (v.startsWith("next")) key = KeyEvent.KEYCODE_MEDIA_NEXT;
                    else if (v.startsWith("prev")) key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                    else return "Ne yapacağımı anlayamadım.";
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                    return null;
                }
                case "torch": {
                    boolean on = !str(p, "value").toLowerCase(Locale.ROOT).startsWith("off");
                    CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
                    for (String id : cm.getCameraIdList()) {
                        Boolean flash = cm.getCameraCharacteristics(id)
                                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        if (flash != null && flash) {
                            cm.setTorchMode(id, on);
                            return null;
                        }
                    }
                    return "Fener bulamadım.";
                }
                case "open_app": {
                    String want = str(p, "app").toLowerCase(TR);
                    if (want.isEmpty()) return "Hangi uygulamayı açayım?";
                    PackageManager pm = c.getPackageManager();
                    Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    String best = null;
                    int bestScore = 0;
                    for (ResolveInfo ri : pm.queryIntentActivities(mainIntent, 0)) {
                        String name = ri.loadLabel(pm).toString().toLowerCase(TR);
                        int score = name.equals(want) ? 3
                                : (name.contains(want) ? 2
                                : ((want.contains(name) && name.length() > 2) ? 1 : 0));
                        if (score > bestScore) {
                            bestScore = score;
                            best = ri.activityInfo.packageName;
                        }
                    }
                    if (best == null) return "Bu adda bir uygulama bulamadım.";
                    Intent li = pm.getLaunchIntentForPackage(best);
                    if (li == null) return "Uygulamayı açamadım.";
                    return launch(c, li);
                }
                case "chords": {
                    String q = str(p, "query");
                    if (q.isEmpty()) return "Hangi şarkının akorlarını bulayım?";
                    return launch(c, new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + Uri.encode(q + " akorlar"))));
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return "Bunu yapamadım.";
        }
    }

    static String str(JSONObject p, String k) {
        if (p == null || p.isNull(k)) return "";
        String s = p.optString(k, "").trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String launch(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return null;
        } catch (Exception e) {
            return "Bunu açamadım.";
        }
    }

    private static String resolveNumber(Context c, JSONObject p) {
        String num = str(p, "number");
        if (!num.isEmpty() && num.matches("[+0-9 ()-]{5,}")) return num;
        String name = str(p, "name");
        if (name.isEmpty() || !has(c, Manifest.permission.READ_CONTACTS)) return null;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name + "%"}, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }
}
