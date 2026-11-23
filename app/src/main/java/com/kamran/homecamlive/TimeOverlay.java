package com.kamran.homecamlive;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Draws live date/time on every frame so it is visible in the feed and in the MP4. */
public final class TimeOverlay {
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private TimeOverlay() { }

    public static Bitmap stamp(Bitmap src) {
        if (src == null) return null;
        Bitmap out = src.copy(src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        float textSize = Math.max(15f, out.getWidth() / 34f);
        float pad = textSize * 0.5f;
        float lineGap = textSize * 1.15f;

        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setColor(0xA6000000);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(textSize);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        long now = System.currentTimeMillis();
        String date = DATE_FMT.format(new Date(now));
        String time = TIME_FMT.format(new Date(now));
        Rect b = new Rect();
        text.getTextBounds(date, 0, date.length(), b);
        float w = b.width();
        text.getTextBounds(time, 0, time.length(), b);
        w = Math.max(w, b.width());
        float boxW = w + pad * 2;
        float boxH = lineGap * 2 + pad * 1.2f;

        c.drawRect(pad, pad, pad + boxW, pad + boxH, box);
        float baseY = pad + lineGap + textSize * 0.15f;
        c.drawText(date, pad * 2, baseY, text);
        c.drawText(time, pad * 2, baseY + lineGap, text);
        return out;
    }
}
