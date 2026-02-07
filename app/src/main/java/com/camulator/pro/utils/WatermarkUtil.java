package com.camulator.pro.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

public class WatermarkUtil {

    public static Bitmap addWatermark(Bitmap src, String logoText, String date, String location) {
        int w = src.getWidth();
        int h = src.getHeight();
        
        // Calculate Footer Size (e.g., 10% of height)
        int footerHeight = (int) (h * 0.12);
        Bitmap output = Bitmap.createBitmap(w, h + footerHeight, Bitmap.Config.ARGB_8888);
        
        Canvas canvas = new Canvas(output);
        
        // Draw Original Image
        canvas.drawBitmap(src, 0, 0, null);
        
        // Draw White Footer Background
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.WHITE);
        canvas.drawRect(0, h, w, h + footerHeight, bgPaint);
        
        // Config Fonts
        Paint textPaint = new Paint();
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(footerHeight * 0.35f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setAntiAlias(true);
        
        Paint smallTextPaint = new Paint(textPaint);
        smallTextPaint.setTextSize(footerHeight * 0.25f);
        smallTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        smallTextPaint.setColor(Color.GRAY);
        
        // Draw Logo (Left)
        float padding = w * 0.04f;
        float textY = h + (footerHeight / 2) + (textPaint.getTextSize() / 3);
        
        canvas.drawText(logoText, padding, textY, textPaint);
        
        // Draw Meta (Right)
        String meta = date + " | " + location;
        float metaWidth = smallTextPaint.measureText(meta);
        canvas.drawText(meta, w - metaWidth - padding, textY, smallTextPaint);
        
        // Draw Red Line Divider (Leica Style)
        Paint linePaint = new Paint();
        linePaint.setColor(Color.RED);
        linePaint.setStrokeWidth(4f);
        canvas.drawLine(w - metaWidth - padding - 20, h + (footerHeight*0.2f), w - metaWidth - padding - 20, h + (footerHeight*0.8f), linePaint);
        
        return output;
    }
}