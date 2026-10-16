package com.ev.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Kameradan gelen fotoğrafı Gemini için küçültür (en fazla 1024 piksel, JPEG). */
final class Img {
    private Img() { }

    static byte[] fromUri(Context c, Uri u) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("fotoğraf açılamadı");
        BitmapFactory.decodeStream(in, null, o);
        in.close();

        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("fotoğraf açılamadı");
        Bitmap b = BitmapFactory.decodeStream(in, null, o);
        in.close();
        if (b == null) throw new Exception("fotoğraf çözülemedi");

        int deg = 0;
        InputStream in2 = null;
        try {
            in2 = c.getContentResolver().openInputStream(u);
            if (in2 != null) {
                ExifInterface ex = new ExifInterface(in2);
                int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            }
        } catch (Exception ignored) {
        } finally {
            if (in2 != null) {
                try { in2.close(); } catch (Exception ignored) { }
            }
        }

        Matrix m = new Matrix();
        if (deg != 0) m.postRotate(deg);
        float scale = 1024f / Math.max(b.getWidth(), b.getHeight());
        if (scale < 1f) m.postScale(scale, scale);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        r.compress(Bitmap.CompressFormat.JPEG, 82, bo);
        return bo.toByteArray();
    }
}
