package com.camulator.pro.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;

public class WatermarkUtil {

    public static class WatermarkConfig {
        public boolean isFooterMode = true; // true = Footer Frame, false = Overlay
        public boolean isWhiteBg = true;
        public int align = 0; // 0=Left, 1=Center, 2=Right
        public float heightPercent = 0.12f; // Controls relative height
        public String customText = "";
        public boolean showDate = true;
        public boolean showGPS = true;
        public boolean showCity = true;
        public boolean showStreet = false;
        public String dateStr = "";
        public String locStr = "";
        public String exifInfo = ""; // Shutter, ISO, Aperture, Focal Length
        public boolean shouldCrop1to1 = false;
    }

    public static Bitmap addWatermark(Bitmap src, WatermarkConfig config) {
        int w = src.getWidth();
        int h = src.getHeight();
        
        // 1. Calculate Layout Dimensions
        // In Footer mode, the footer height is explicitly controlled by the slider (heightPercent).
        // In Overlay mode, we calculate a virtual "zone" to size text proportionally.
        int footerHeight = config.isFooterMode ? (int) (Math.max(w, h) * config.heightPercent) : 0;
        
        // Ensure footer isn't too tiny to read
        if (config.isFooterMode && footerHeight < Math.max(w, h) * 0.05f) {
             footerHeight = (int)(Math.max(w, h) * 0.05f); 
        }

        int outputH = h + footerHeight;
        
        Bitmap output = Bitmap.createBitmap(w, outputH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // 2. Draw Original Image
        canvas.drawBitmap(src, 0, 0, null);
        
        // 3. Setup Colors
        int bgColor = config.isWhiteBg ? 0xFFFFFFFF : 0xFF121212; // Slightly softer black
        int mainTextColor = config.isWhiteBg ? 0xFF212121 : 0xFFEEEEEE;
        int subTextColor = config.isWhiteBg ? 0xFF757575 : 0xFF9E9E9E;
        int accentColor = 0xFFD32F2F; // Leica-ish Red line
        
        Paint bgPaint = new Paint();
        bgPaint.setColor(bgColor);
        bgPaint.setStyle(Paint.Style.FILL);
        
        // 4. Draw Footer / Overlay Scrim
        if (config.isFooterMode) {
            canvas.drawRect(0, h, w, outputH, bgPaint);
        } else {
            // Overlay Mode: Clean Gradient
            int scrimHeight = (int) (h * 0.35f);
            Paint scrimPaint = new Paint();
            scrimPaint.setShader(new LinearGradient(
                    0, h - scrimHeight, 
                    0, h, 
                    0x00000000, 
                    0xCC000000, 
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - scrimHeight, w, h, scrimPaint);
            
            mainTextColor = 0xFFFFFFFF;
            subTextColor = 0xFFDDDDDD; 
        }
        
        // 5. Prepare Text Strings
        String titleText = config.customText.isEmpty() ? "Camulator Pro" : config.customText;
        String modelText = android.os.Build.MODEL.toUpperCase();
        if (modelText.length() > 10) modelText = android.os.Build.MANUFACTURER.toUpperCase();

        // Combine date and location nicely
        StringBuilder metaBuilder = new StringBuilder();
        if (config.showDate && !config.dateStr.isEmpty()) {
            metaBuilder.append(config.dateStr);
        }
        if (!config.locStr.isEmpty()) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(config.locStr);
        }
        String metaText = metaBuilder.toString();
        String exifText = config.exifInfo;

        // 6. Typography & Sizing
        // Base Unit calculation
        float baseUnit = config.isFooterMode ? footerHeight : (Math.min(w, h) * 0.15f);
        
        float titleSize = baseUnit * 0.28f; 
        float exifSize = baseUnit * 0.19f;
        float metaSize = baseUnit * 0.17f;

        // Paints
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(mainTextColor);
        titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titlePaint.setTextSize(titleSize);
        // Letter spacing for "Premium" feel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            titlePaint.setLetterSpacing(0.05f);
        }

        Paint exifPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        exifPaint.setColor(mainTextColor); 
        exifPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); // Bold EXIF looks better
        exifPaint.setTextSize(exifSize);

        Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(subTextColor);
        metaPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        metaPaint.setTextSize(metaSize);

        // Divider Line Paint
        Paint linePaint = new Paint();
        linePaint.setColor(subTextColor); 
        linePaint.setStrokeWidth(baseUnit * 0.02f); 
        linePaint.setAlpha(80);

        // Shadows for Overlay mode
        if (!config.isFooterMode) {
            titlePaint.setShadowLayer(4, 2, 2, 0xAA000000);
            exifPaint.setShadowLayer(3, 1, 1, 0xAA000000);
            metaPaint.setShadowLayer(3, 1, 1, 0xAA000000);
        }

        // 7. Layout Calculation
        float paddingX = w * 0.05f; // 5% padding from sides
        
        float centerY; 
        if (config.isFooterMode) {
            centerY = h + (footerHeight / 2f);
        } else {
            centerY = h - (baseUnit * 0.6f); 
        }

        // Font Metrics for vertical centering
        Paint.FontMetrics titleFm = titlePaint.getFontMetrics();
        float titleHalfH = (titleFm.descent - titleFm.ascent) / 2f - titleFm.descent;
        
        Paint.FontMetrics exifFm = exifPaint.getFontMetrics();
        float exifH = exifFm.descent - exifFm.ascent;

        // --- Drawing Logic ---

        if (config.align == 1) { 
            // === CENTER ALIGN ===
            // Stacked: Title -> EXIF -> Meta
            titlePaint.setTextAlign(Paint.Align.CENTER);
            exifPaint.setTextAlign(Paint.Align.CENTER);
            metaPaint.setTextAlign(Paint.Align.CENTER);
            
            float totalH = (titleFm.descent - titleFm.ascent) + (exifH) + (metaSize * 2f);
            float startY = centerY - (totalH / 2f) + Math.abs(titleFm.ascent);
            
            canvas.drawText(titleText, w / 2f, startY, titlePaint);
            
            startY += titleFm.descent + (baseUnit * 0.15f) + Math.abs(exifFm.ascent);
            canvas.drawText(exifText.isEmpty() ? modelText : exifText, w / 2f, startY, exifPaint);
            
            startY += exifFm.descent + (baseUnit * 0.1f) + Math.abs(metaPaint.getFontMetrics().ascent);
            canvas.drawText(metaText, w / 2f, startY, metaPaint);

        } else if (config.align == 2) {
            // === RIGHT ALIGN ===
            // Everything right aligned
            float rightX = w - paddingX;
            titlePaint.setTextAlign(Paint.Align.RIGHT);
            exifPaint.setTextAlign(Paint.Align.RIGHT);
            metaPaint.setTextAlign(Paint.Align.RIGHT);
            
            canvas.drawText(titleText, rightX, centerY + titleHalfH - (exifH * 0.8f), titlePaint);
            
            // Divider
            if (config.isFooterMode) {
                 float lineY = centerY + titleHalfH + (baseUnit * 0.1f);
                 canvas.drawLine(rightX, lineY, rightX - (w*0.3f), lineY, linePaint);
            }
            
            canvas.drawText(exifText + " " + metaText, rightX, centerY + titleHalfH + (exifH * 1.2f), exifPaint);

        } else {
            // === LEFT / SPLIT ALIGN (Default Professional Look) ===
            // Left: Title (Logo)
            // Right: Data (EXIF top, Date/Loc bottom)
            // Divider: Vertical line in between
            
            float leftX = paddingX;
            float rightX = w - paddingX;
            
            titlePaint.setTextAlign(Paint.Align.LEFT);
            // Draw Title vertically centered
            canvas.drawText(titleText, leftX, centerY + titleHalfH, titlePaint);
            
            // Draw Right Side Data
            exifPaint.setTextAlign(Paint.Align.RIGHT);
            metaPaint.setTextAlign(Paint.Align.RIGHT);
            
            float dataCenterY = centerY;
            
            // EXIF Line
            canvas.drawText(exifText, rightX, dataCenterY - (baseUnit * 0.08f), exifPaint);
            
            // Meta Line
            canvas.drawText(metaText, rightX, dataCenterY + exifH + (baseUnit * 0.08f), metaPaint);
            
            // Vertical Divider
            if (config.isFooterMode) {
                float titleWidth = titlePaint.measureText(titleText);
                float lineX = leftX + titleWidth + (w * 0.04f);
                
                // Only draw divider if it doesn't overlap right text
                float maxRightTextW = Math.max(exifPaint.measureText(exifText), metaPaint.measureText(metaText));
                if (lineX < (rightX - maxRightTextW - (w * 0.04f))) {
                    float lineH = baseUnit * 0.5f;
                    canvas.drawLine(lineX, centerY - lineH/2, lineX, centerY + lineH/2, linePaint);
                    
                    // Draw Model Name next to divider if space permits
                    Paint modelPaint = new Paint(metaPaint);
                    modelPaint.setColor(subTextColor);
                    modelPaint.setTextAlign(Paint.Align.LEFT);
                    modelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                    canvas.drawText(modelText, lineX + (w * 0.04f), centerY + titleHalfH, modelPaint);
                }
            }
        }

        return output;
    }
}