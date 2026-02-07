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
        public float heightPercent = 0.12f; // Increased default for better spacing
        public String customText = "";
        public boolean showDate = true;
        public boolean showGPS = true;
        public boolean showCity = true;
        public boolean showStreet = false;
        public String dateStr = "";
        public String locStr = "";
        public String exifInfo = ""; // New: Shutter, ISO, Aperture, etc.
    }

    public static Bitmap addWatermark(Bitmap src, WatermarkConfig config) {
        int w = src.getWidth();
        int h = src.getHeight();
        
        // Calculate dimensions
        int footerHeight = config.isFooterMode ? (int) (Math.max(w, h) * config.heightPercent) : 0;
        // Constraint footer height to sensible limits relative to image
        if (config.isFooterMode) {
             footerHeight = Math.max(footerHeight, 150); 
        }

        int outputH = h + footerHeight;
        
        Bitmap output = Bitmap.createBitmap(w, outputH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // 1. Draw Original Image
        canvas.drawBitmap(src, 0, 0, null);
        
        // 2. Setup Base Paints
        int bgColor = config.isWhiteBg ? Color.WHITE : Color.BLACK;
        int mainTextColor = config.isWhiteBg ? Color.BLACK : Color.WHITE;
        int subTextColor = config.isWhiteBg ? 0xFF666666 : 0xFFAAAAAA; // Dark Grey or Light Grey
        
        Paint bgPaint = new Paint();
        bgPaint.setColor(bgColor);
        bgPaint.setStyle(Paint.Style.FILL);
        
        // 3. Draw Background / Scrim
        if (config.isFooterMode) {
            canvas.drawRect(0, h, w, outputH, bgPaint);
        } else {
            // Overlay Mode: Draw gradient scrim at bottom for readability
            int scrimHeight = (int) (h * 0.25f);
            Paint scrimPaint = new Paint();
            scrimPaint.setShader(new LinearGradient(
                    0, h - scrimHeight, 
                    0, h, 
                    0x00000000, 
                    0x99000000, // Semi-transparent black
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - scrimHeight, w, h, scrimPaint);
            
            // Force text colors for Overlay
            mainTextColor = Color.WHITE;
            subTextColor = 0xFFDDDDDD; 
        }
        
        // 4. Prepare Text Content
        String titleText = config.customText.isEmpty() ? "Camulator Pro" : config.customText;
        
        // Construct Metadata String (Line 2)
        StringBuilder metaBuilder = new StringBuilder();
        if (config.showDate && !config.dateStr.isEmpty()) {
            metaBuilder.append(config.dateStr);
        }
        if (!config.locStr.isEmpty()) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(config.locStr);
        }
        String metaText = metaBuilder.toString();
        
        // Exif String (Line 1 of details)
        String exifText = config.exifInfo;

        // 5. Configure Text Paints
        // Base Unit based on width to scale with image resolution
        float baseUnit = Math.min(w, h) / 1000f; 
        
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(mainTextColor);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setTextSize(baseUnit * 45f); // Large Title

        Paint exifPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        exifPaint.setColor(mainTextColor); // Exif usually same as title or slightly lighter
        exifPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        exifPaint.setTextSize(baseUnit * 28f);

        Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(subTextColor);
        metaPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        metaPaint.setTextSize(baseUnit * 26f);

        // Shadow for overlay mode only
        if (!config.isFooterMode) {
            titlePaint.setShadowLayer(4, 2, 2, Color.BLACK);
            exifPaint.setShadowLayer(3, 1, 1, Color.BLACK);
            metaPaint.setShadowLayer(3, 1, 1, Color.BLACK);
        }

        // 6. Layout Logic
        float paddingX = w * 0.04f;
        float paddingY = footerHeight * 0.25f; // Vertical padding inside footer
        
        // Y Position Bases
        float centerY; 
        if (config.isFooterMode) {
            centerY = h + (footerHeight / 2f);
        } else {
            centerY = h - (h * 0.08f); // Bottom area overlay
        }
        
        Paint.FontMetrics titleFm = titlePaint.getFontMetrics();
        float titleHeight = titleFm.descent - titleFm.ascent;
        
        Paint.FontMetrics exifFm = exifPaint.getFontMetrics();
        float exifHeight = exifFm.descent - exifFm.ascent;
        
        // Separator Paint
        Paint linePaint = new Paint();
        linePaint.setColor(0xFFD32F2F); // Leica Red / Material Red 700
        linePaint.setStrokeWidth(baseUnit * 4f); 

        // --- Drawing based on Alignment ---
        
        if (config.align == 1) { 
            // === CENTER ALIGNMENT (Stacked, Minimalist) ===
            titlePaint.setTextAlign(Paint.Align.CENTER);
            exifPaint.setTextAlign(Paint.Align.CENTER);
            metaPaint.setTextAlign(Paint.Align.CENTER);
            
            float currentY = centerY - (titleHeight + exifHeight + 20) / 2f + Math.abs(titleFm.ascent);
            
            // Draw Title
            canvas.drawText(titleText, w / 2f, currentY, titlePaint);
            
            // Draw Exif below Title
            currentY += titleFm.descent + 10 + Math.abs(exifFm.ascent);
            canvas.drawText(exifText.isEmpty() ? metaText : exifText, w / 2f, currentY, exifPaint);
            
            // If we have both Exif and Meta, maybe skip Meta in Center mode to avoid clutter, 
            // or draw it very small. Let's draw Meta if Exif exists.
            if (!exifText.isEmpty() && !metaText.isEmpty()) {
                currentY += exifFm.descent + 10 + Math.abs(metaPaint.getFontMetrics().ascent);
                 canvas.drawText(metaText, w / 2f, currentY, metaPaint);
            }

        } else if (config.align == 2) {
            // === RIGHT ALIGNMENT ===
            // Not strictly right-justified text, but content on the right side.
            // Professional Look: 
            // [Meta/Exif] | [Title] (Right aligned)
            
            float rightEdge = w - paddingX;
            
            // 1. Draw Title on Right
            titlePaint.setTextAlign(Paint.Align.RIGHT);
            float titleBaseY = centerY - (titleFm.ascent + titleFm.descent) / 2f;
            canvas.drawText(titleText, rightEdge, titleBaseY, titlePaint);
            
            // 2. Draw Separator Line to the left of Title
            float titleW = titlePaint.measureText(titleText);
            float lineX = rightEdge - titleW - (paddingX * 0.8f);
            
            // Only draw line and details if footer mode
            if (config.isFooterMode) {
                float lineH = titleHeight * 0.8f;
                canvas.drawLine(lineX, centerY - lineH/2, lineX, centerY + lineH/2, linePaint);
                
                // 3. Draw Exif and Meta to the LEFT of the line
                exifPaint.setTextAlign(Paint.Align.RIGHT);
                metaPaint.setTextAlign(Paint.Align.RIGHT);
                
                float detailsRight = lineX - (paddingX * 0.8f);
                float halfGap = (exifHeight * 0.15f);
                
                // Exif (Top)
                canvas.drawText(exifText, detailsRight, centerY - halfGap, exifPaint);
                // Meta (Bottom)
                canvas.drawText(metaText, detailsRight, centerY + exifHeight - halfGap, metaPaint);
            } else {
                 // In overlay, just stack them on right
                 float y = titleBaseY + titleHeight * 0.8f;
                 exifPaint.setTextAlign(Paint.Align.RIGHT);
                 canvas.drawText(exifText + "  " + metaText, rightEdge, y, exifPaint);
            }
            
        } else {
            // === LEFT ALIGNMENT (Default Pro Look) ===
            // [Title] | [Exif]
            //         | [Meta]
            
            float leftEdge = paddingX;
            
            // 1. Draw Title on Left
            titlePaint.setTextAlign(Paint.Align.LEFT);
            float titleBaseY = centerY - (titleFm.ascent + titleFm.descent) / 2f;
            canvas.drawText(titleText, leftEdge, titleBaseY, titlePaint);
            
            if (config.isFooterMode) {
                // 2. Draw Separator Line to the right of Title
                float titleW = titlePaint.measureText(titleText);
                float lineX = leftEdge + titleW + (paddingX * 0.8f);
                
                float lineH = titleHeight * 0.8f;
                canvas.drawLine(lineX, centerY - lineH/2, lineX, centerY + lineH/2, linePaint);
                
                // 3. Draw Details to right of Line
                exifPaint.setTextAlign(Paint.Align.LEFT);
                metaPaint.setTextAlign(Paint.Align.LEFT);
                
                float detailsLeft = lineX + (paddingX * 0.8f);
                float halfGap = (exifHeight * 0.15f);
                
                // Exif (Top)
                canvas.drawText(exifText, detailsLeft, centerY - halfGap, exifPaint);
                // Meta (Bottom)
                // Use ascent to align baseline correctly relative to center
                Paint.FontMetrics metaFm = metaPaint.getFontMetrics();
                float metaBaseY = centerY + exifHeight - halfGap; 
                // Adjust if only one line exists? No, keep layout consistent.
                canvas.drawText(metaText, detailsLeft, metaBaseY, metaPaint);
            } else {
                // Overlay: Stack below title
                float y = titleBaseY + titleHeight * 0.8f;
                exifPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(exifText + "  " + metaText, leftEdge, y, exifPaint);
            }
        }

        return output;
    }
}
