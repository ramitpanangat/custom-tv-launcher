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

    /**
     * The palette colour closest to this one: hue first, then saturation and brightness. A grey has no meaningful
     * hue, so for those the hue counts for little and a muted palette colour (steel, mauve) wins.
     */
    static int nearest(int color, int[] palette) {
        if (palette.length == 0) return color;
        float[] c = new float[3], p = new float[3];
        Color.colorToHSV(color, c);
        int best = palette[0];
        float bestDistance = Float.MAX_VALUE;
        for (int candidate : palette) {
            Color.colorToHSV(candidate, p);
            float dh = Math.abs(c[0] - p[0]);
            dh = Math.min(dh, 360f - dh) / 180f;
            float hueWeight = c[1] < 0.2f ? 0.2f : 2f;
            float distance = hueWeight * dh + Math.abs(c[1] - p[1]) + Math.abs(c[2] - p[2]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Turns an icon into a white silhouette in place: light pixels stay (as white), dark and coloured ones fade
     * out. An official logo with white lettering on its own background (Prime Video) keeps its lettering and
     * drops the background, so it sits on the tile colour like the white glyphs do.
     */
    static void whiten(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float light = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
            float keep = clamp((light - 0.55f) / 0.35f, 0f, 1f);
            px[i] = (Math.round(Color.alpha(c) * keep) << 24) | 0x00FFFFFF;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
