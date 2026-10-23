package com.tvtiles.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** A tile colour from an app's artwork: the most common strong hue, kept dark enough for white text. */
final class AppColors {
    static final int FALLBACK = 0xFF3A3A3C;

    private AppColors() { }

    static int sample(Drawable artwork) {
        if (artwork == null) return FALLBACK;
        Drawable d = artwork.getConstantState() != null ? artwork.getConstantState().newDrawable().mutate() : artwork;
        int size = 32;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bitmap));
        float[] weight = new float[36], hue = new float[36], sat = new float[36], val = new float[36];
        float[] hsv = new float[3];
        float total = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = bitmap.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;   // greys, white and black say nothing
                int bin = ((int) (hsv[0] / 10f)) % 36;
                float w = hsv[1] * hsv[2];
                weight[bin] += w;
                hue[bin] += hsv[0] * w;
                sat[bin] += hsv[1] * w;
                val[bin] += hsv[2] * w;
                total += w;
            }
        }
        bitmap.recycle();
        int best = 0;
        for (int i = 1; i < 36; i++) if (weight[i] > weight[best]) best = i;
        if (total < 4f || weight[best] <= 0f) return FALLBACK;
        float w = weight[best];
        return Color.HSVToColor(new float[]{
                hue[best] / w,
                clamp(sat[best] / w, 0.45f, 0.90f),
                clamp(val[best] / w, 0.40f, 0.72f)});
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
