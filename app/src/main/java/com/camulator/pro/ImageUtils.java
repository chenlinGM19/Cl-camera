package com.camulator.pro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ImageUtils {

    public enum FilterType {
        NONE, FUJI, LEICA, BW
    }

    public static Bitmap processImage(Bitmap original, FilterType filterType, List<PointF> curvePoints, WatermarkConfig wmConfig) {
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);
        Paint paint = new Paint();

        // 1. Apply Filters (ColorMatrix)
        ColorMatrix cm = new ColorMatrix();
        switch (filterType) {
            case FUJI:
                // Boost Greens, slight contrast
                cm.set(new float[] {
                    1.1f, 0.1f, 0, 0, 0,
                    0, 1.2f, 0, 0, 0,
                    0, 0, 1.0f, 0, 0,
                    0, 0, 0, 1, 0
                });
                break;
            case LEICA:
                // High contrast, slight red tint
                cm.setSaturation(1.1f);
                // Simple approximation
                break;
            case BW:
                cm.setSaturation(0);
                break;
            default:
                break;
        }
        
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(original, 0, 0, paint);
        
        // 2. Apply Curves (Simple global gamma approximation for demo)
        // Note: Real RGB curve implementation requires iterating pixels or RenderScript/OpenGL
        // This is a placeholder for curve application.
        
        // 3. Apply Watermark
        if (wmConfig.enabled) {
            drawWatermark(canvas, mutable.getWidth(), mutable.getHeight(), wmConfig);
        }

        return mutable;
    }

    private static void drawWatermark(Canvas canvas, int w, int h, WatermarkConfig config) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);
        
        float textSize = h * (config.textSize == 0 ? 0.02f : config.textSize == 1 ? 0.035f : 0.05f);
        textPaint.setTextSize(textSize);

        int padding = (int) (w * 0.05f);
        int yPos = h - padding;

        if (config.showLogo) {
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            canvas.drawText(config.customText, padding, yPos, textPaint);
            yPos -= (textSize * 1.5);
        }

        if (config.showLocation || config.showCoords || config.showTime) {
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            textPaint.setTextSize(textSize * 0.8f);
            
            StringBuilder sb = new StringBuilder();
            if (config.showTime) {
                sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("  ");
            }
            if (config.showCoords) {
                sb.append(config.latLng);
            }
            
            String info = sb.toString();
            float textW = textPaint.measureText(info);
            canvas.drawText(info, w - padding - textW, h - padding, textPaint);
        }
        
        // Frame
        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(w * 0.005f);
        canvas.drawRect(padding/2, padding/2, w - padding/2, h - padding/2, borderPaint);
    }

    public static class WatermarkConfig {
        public boolean enabled = true;
        public boolean showLogo = true;
        public String customText = "CAMULATOR";
        public boolean showTime = true;
        public boolean showCoords = true;
        public boolean showLocation = false;
        public String latLng = "0.0, 0.0";
        public int textSize = 1; // 0=small, 1=med, 2=large
    }
}