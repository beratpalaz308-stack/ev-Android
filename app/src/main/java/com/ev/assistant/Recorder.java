package com.ev.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Konuşmayı kaydeder, sen susunca durur. Bip yok. Arka plan iş parçacığında çağır. */
final class Recorder {
    private static final int RATE = 16000;

    private Recorder() { }

    /**
     * @param waitSpeechMs konuşma başlamazsa en fazla bu kadar bekle
     * @param maxMs        toplam kayıt üst sınırı
     * @return WAV baytları, konuşma yoksa null
     */
    static byte[] record(int waitSpeechMs, int maxMs) {
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, 8192);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) return null;

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            short[] frame = new short[320];
            double noise = 0;
            int calib = 0;
            boolean speech = false;
            int silentMs = 0;
            int speechMs = 0;
            int elapsed = 0;

            ar.startRecording();
            while (elapsed < maxMs) {
                int n = ar.read(frame, 0, frame.length);
                if (n <= 0) break;
                elapsed += 20;
                double rms = rms(frame, n);
                write(pcm, frame, n);

                if (calib < 15) {
                    noise += rms;
                    calib++;
                    if (calib == 15) noise = Math.min(noise / 15, 1500);
                    continue;
                }

                double thr = Math.max(noise * 2.5, 600);
                if (rms > thr) {
                    speech = true;
                    silentMs = 0;
                    speechMs += 20;
                } else if (speech) {
                    silentMs += 20;
                    if (silentMs >= 1000 && speechMs >= 200) break;
                } else if (elapsed >= waitSpeechMs) {
                    return null;
                }
            }
            if (!speech) return null;
            return wav(pcm.toByteArray());
        } catch (Throwable t) {
            return null;
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Throwable ignored) { }
                try { ar.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private static double rms(short[] f, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) f[i] * f[i];
        return Math.sqrt(sum / Math.max(n, 1));
    }

    private static void write(ByteArrayOutputStream o, short[] f, int n) {
        for (int i = 0; i < n; i++) {
            o.write(f[i] & 0xff);
            o.write((f[i] >> 8) & 0xff);
        }
    }

    private static byte[] wav(byte[] pcm) {
        int len = pcm.length;
        ByteBuffer b = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + len);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(RATE);
        b.putInt(RATE * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(len);
        b.put(pcm);
        return b.array();
    }
}
