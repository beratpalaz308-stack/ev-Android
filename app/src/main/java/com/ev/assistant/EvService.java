package com.ev.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.TelephonyManager;

/** Ön plandaki servis: "hey ev" kelimesini telefonun içinde, bipsiz dinler. */
public class EvService extends Service {
    static final String ACT_LISTEN = "com.ev.assistant.LISTEN";
    private static final String CH = "ev_channel";

    private Handler main;
    private EvCore core;
    private Wake wake;
    private PowerManager.WakeLock wl;
    private BroadcastReceiver phoneRx;
    private boolean running = true;
    private boolean voskBroken = false;
    private boolean ringing = false;
    private int failCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        try {
            NotificationChannel c = new NotificationChannel(CH, "EV", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
            Notification n = buildNotif("EV hazırlanıyor...");
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            running = false;
            stopSelf();
            return;
        }

        core = EvCore.get(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ev:wake");
        wl.acquire();

        registerPhoneReceiver();
        main.postDelayed(this::startWake, 800);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (running && i != null && ACT_LISTEN.equals(i.getAction())) {
            main.post(this::listenNow);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        if (wake != null) wake.release();
        if (phoneRx != null) {
            try { unregisterReceiver(phoneRx); } catch (Exception ignored) { }
        }
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    // ---------------------------------------------------------------- dinleme

    private boolean voskOn() {
        return getSharedPreferences("ev", MODE_PRIVATE).getBoolean("vosk", false)
                && Wake.modelDir(this) != null;
    }

    private void startWake() {
        if (!running) return;
        if (wake != null) wake.stop();
        if (!voskOn() || voskBroken) {
            updateNotif("Konuşmak için uygulamadaki mikrofona dokun");
            return;
        }
        if (wake == null) {
            wake = new Wake(this, new Wake.Callback() {
                @Override
                public void onWake(final String heard) {
                    main.post(() -> onWakeHeard(heard));
                }

                @Override
                public void onFail() {
                    main.post(() -> wakeFailed());
                }
            });
        }
        if (!wake.start()) {
            wakeFailed();
            return;
        }
        failCount = 0;
        updateNotif("Dinliyorum: \"hey ev\" de");
    }

    private void wakeFailed() {
        failCount++;
        if (failCount < 4) {
            updateNotif("Çevrimdışı dinleme yeniden deneniyor...");
            main.postDelayed(this::startWake, 15000);
        } else {
            voskBroken = true;
            updateNotif("Çevrimdışı dinleme çalışmadı, mikrofon düğmesini kullan");
        }
    }

    private void onWakeHeard(String heard) {
        if (!running) return;
        if (core.isBusy() || ringing) {
            startWake();
            return;
        }
        if (wake != null) wake.stop();
        updateNotif("Duydum: hey ev");
        core.voiceTurn(true, () -> main.post(this::startWake));
    }

    /** Uygulamadaki mikrofon düğmesi. */
    private void listenNow() {
        if (core.isBusy()) return;
        if (wake != null) wake.stop();
        core.voiceTurn(false, () -> main.post(this::startWake));
    }

    // ---------------------------------------------------------------- telefon çalarsa

    private void registerPhoneReceiver() {
        phoneRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String s = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (TelephonyManager.EXTRA_STATE_RINGING.equals(s)) {
                    if (ringing || core.isBusy()) return;
                    ringing = true;
                    if (wake != null) wake.stop();
                    core.callTurn(() -> main.post(() -> startWake()));
                } else {
                    ringing = false;
                }
            }
        };
        IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneRx, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(phoneRx, f);
        }
    }

    // ---------------------------------------------------------------- bildirim

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, ChatActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH)
                .setContentTitle("EV")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_ev)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String text) {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, buildNotif(text));
        } catch (Exception ignored) { }
    }
}
