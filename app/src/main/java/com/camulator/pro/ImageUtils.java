package com.camulator.pro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
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
        // Ensure image is mutable
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);
        Paint paint = new Paint();

        // 1. Apply Filters (ColorMatrix)
        ColorMatrix cm = new ColorMatrix();
        switch (filterType) {
            case FUJI:
                // Classic Chrome-ish: Boost Greens/Reds, lower saturation in blues, slight contrast
                cm.set(new float[] {
                    1.1f, 0.05f, 0.05f, 0, -10,
                    0.05f, 1.1f, 0.05f, 0, -10,
                    0.05f, 0.05f, 1.0f, 0, -10,
                    0, 0, 0, 1, 0
                });
                break;
            case LEICA:
                // High contrast, crush blacks, slight warming
                ColorMatrix sat = new ColorMatrix();
                sat.setSaturation(1.2f);
                ColorMatrix contrast = new ColorMatrix();
                float scale = 1.1f;
                float translate = (-.5f * scale + .5f) * 255.f;
                contrast.set(new float[] {
                    scale, 0, 0, 0, translate,
                    0, scale, 0, 0, translate,
                    0, 0, scale, 0, translate,
                    0, 0, 0, 1, 0
                });
                cm.setConcat(sat, contrast);
                break;
            case BW:
                cm.setSaturation(0);
                break;
            default:
                break;
        }
        
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(original, 0, 0, paint);
        
        // 2. Apply Curves 
        // (Placeholder: Real curve implementation requires per-pixel manipulation or RenderScript)

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
        // Add shadow for better visibility on bright images
        textPaint.setShadowLayer(4f, 2f, 2f, Color.parseColor("#80000000"));
        
        // Base sizes relative to image height
        float textSize = h * (config.textSize == 0 ? 0.02f : config.textSize == 1 ? 0.03f : 0.045f);
        textPaint.setTextSize(textSize);

        int padding = (int) (w * 0.04f);
        int bottomMargin = (int) (h * 0.04f);

        // Prepare Text Content
        String primaryText = config.showLogo ? config.customText : "";
        
        StringBuilder metaSb = new StringBuilder();
        boolean hasMeta = false;
        
        if (config.showTime) {
            metaSb.append(new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date()));
            hasMeta = true;
        }
        if (config.showPlace && config.placeName != null && !config.placeName.isEmpty()) {
            if (hasMeta) metaSb.append(" | ");
            metaSb.append(config.placeName);
            hasMeta = true;
        }
        if (config.showCoords) {
            if (hasMeta) metaSb.append(" | ");
            metaSb.append(config.latLng);
        }
        String secondaryText = metaSb.toString();

        // Calculate Measurements
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float primaryWidth = textPaint.measureText(primaryText);
        
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        textPaint.setTextSize(textSize * 0.75f); // Secondary text is smaller
        float secondaryWidth = textPaint.measureText(secondaryText);
        
        // Reset for drawing
        float secondaryHeight = textSize * 0.75f;
        float totalBlockHeight = textSize + (hasMeta ? secondaryHeight * 1.5f : 0);

        // Determine X Position
        float startX = padding; // Default Left
        
        if (config.position == 1) { // Center
            startX = (w - Math.max(primaryWidth, secondaryWidth)) / 2;
        } else if (config.position == 2) { // Right
            startX = w - padding - Math.max(primaryWidth, secondaryWidth);
        }

        float currentY = h - bottomMargin - totalBlockHeight + textSize;

        // Draw Primary
        if (!primaryText.isEmpty()) {
            textPaint.setTextSize(textSize);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            // Adjust alignment for right/center to ensure lines match up visually
            if (config.position == 1) {
                float offset = (Math.max(primaryWidth, secondaryWidth) - primaryWidth) / 2;
                canvas.drawText(primaryText, startX + offset, currentY, textPaint);
            } else if (config.position == 2) {
                float offset = Math.max(primaryWidth, secondaryWidth) - primaryWidth;
                canvas.drawText(primaryText, startX + offset, currentY, textPaint);
            } else {
                canvas.drawText(primaryText, startX, currentY, textPaint);
            }
            
            currentY += secondaryHeight * 1.5f;
        }

        // Draw Secondary
        if (!secondaryText.isEmpty()) {
            textPaint.setTextSize(textSize * 0.75f);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
             if (config.position == 1) {
                float offset = (Math.max(primaryWidth, secondaryWidth) - secondaryWidth) / 2;
                canvas.drawText(secondaryText, startX + offset, currentY, textPaint);
            } else if (config.position == 2) {
                float offset = Math.max(primaryWidth, secondaryWidth) - secondaryWidth;
                canvas.drawText(secondaryText, startX + offset, currentY, textPaint);
            } else {
                canvas.drawText(secondaryText, startX, currentY, textPaint);
            }
        }
        
        // Optional Aesthetic Line (Leica style) if enabled or implicit in design
        // Drawing a small vertical bar if Left aligned
        if (config.position == 0 && !primaryText.isEmpty()) {
            Paint linePaint = new Paint();
            linePaint.setColor(Color.RED);
            linePaint.setStrokeWidth(8f);
            canvas.drawLine(startX - 20, h - bottomMargin - totalBlockHeight, startX - 20, h - bottomMargin, linePaint);
        }
    }

    public static class WatermarkConfig {
        public boolean enabled = true;
        public boolean showLogo = true;
        public String customText = "CAMULATOR";
        public boolean showTime = true;
        public boolean showCoords = true;
        public boolean showPlace = false;
        public String latLng = "";
        public String placeName = "";
        public int textSize = 1; // 0=small, 1=med, 2=large
        public int position = 0; // 0=Left, 1=Center, 2=Right
    }
}