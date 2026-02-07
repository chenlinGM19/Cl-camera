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
        
        // Visibility Flags
        public boolean showLogo = true;
        public boolean showDate = true;
        public boolean showGPS = true;
        public boolean showCity = true;
        public boolean showStreet = false;
        
        public String dateStr = "";
        public String gpsStr = ""; // Coordinates
        public String locStr = ""; // City/Street text
        public String exifInfo = ""; // Shutter, ISO, Aperture, Focal Length
        public boolean shouldCrop1to1 = false;
    }

    public static Bitmap addWatermark(Bitmap src, WatermarkConfig config) {
        int w = src.getWidth();
        int h = src.getHeight();
        
        // 1. Calculate Layout Dimensions
        // Use max dimension to keep scale consistent across orientations
        int refDim = Math.max(w, h);
        
        // Calculate Footer Height directly from percent (0% - 10%)
        int footerHeight = (int) (refDim * config.heightPercent);
        
        int outputH = config.isFooterMode ? h + footerHeight : h;
        
        Bitmap output = Bitmap.createBitmap(w, outputH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // 2. Draw Original Image
        canvas.drawBitmap(src, 0, 0, null);
        
        // 3. Setup Colors
        int bgColor = config.isWhiteBg ? 0xFFFFFFFF : 0xFF121212; // Softer black
        int mainTextColor = config.isWhiteBg ? 0xFF000000 : 0xFFFFFFFF;
        int subTextColor = config.isWhiteBg ? 0xFF666666 : 0xFFAAAAAA;
        int dividerColor = config.isWhiteBg ? 0xFFDDDDDD : 0xFF333333;
        
        Paint bgPaint = new Paint();
        bgPaint.setColor(bgColor);
        bgPaint.setStyle(Paint.Style.FILL);
        
        // 4. Draw Footer / Overlay Gradient
        float startY = config.isFooterMode ? h : h - footerHeight;
        
        if (footerHeight > 0) {
            if (config.isFooterMode) {
                canvas.drawRect(0, h, w, outputH, bgPaint);
            } else {
                // High-quality gradient for overlay
                Paint scrimPaint = new Paint();
                scrimPaint.setShader(new LinearGradient(
                        0, h - (footerHeight * 1.5f), 
                        0, h, 
                        0x00000000, 
                        0xCC000000, 
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, h - (footerHeight * 1.5f), w, h, scrimPaint);
                
                mainTextColor = 0xFFFFFFFF;
                subTextColor = 0xFFCCCCCC; 
                dividerColor = 0xFF555555;
            }
        }
        
        // 5. Prepare Text Content
        String titleText = config.showLogo ? (config.customText.isEmpty() ? "Camulator Pro" : config.customText) : "";
        String modelText = android.os.Build.MODEL.toUpperCase();
        
        // Build Meta Text (Date | GPS | Loc)
        StringBuilder metaBuilder = new StringBuilder();
        
        if (config.showDate && !config.dateStr.isEmpty()) {
            metaBuilder.append(config.dateStr);
        }
        
        if (config.showGPS && !config.gpsStr.isEmpty()) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(config.gpsStr);
        }
        
        // Build Location String based on flags
        String fullLoc = "";
        if (config.showCity || config.showStreet) {
            String[] parts = config.locStr.split("\\|"); // Expecting "City|Street" format or similar
            String city = parts.length > 0 ? parts[0] : config.locStr;
            String street = parts.length > 1 ? parts[1] : "";
            
            StringBuilder locBuilder = new StringBuilder();
            if (config.showCity && !city.isEmpty()) locBuilder.append(city);
            if (config.showStreet && !street.isEmpty()) {
                if (locBuilder.length() > 0) locBuilder.append(", ");
                locBuilder.append(street);
            }
            fullLoc = locBuilder.toString();
        }
        
        if (!fullLoc.isEmpty()) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(fullLoc);
        }
        
        String metaText = metaBuilder.toString();
        String exifText = config.exifInfo;
        if (exifText.isEmpty()) exifText = "RAW";

        // 6. Dynamic Font Sizing
        float containerH = footerHeight;
        if (containerH <= 0) return output;

        float paddingEdge = w * 0.04f; 
        
        float titleTextSize = containerH * 0.32f; 
        float exifTextSize = containerH * 0.22f;
        float metaTextSize = containerH * 0.18f;
        
        if (titleTextSize > w * 0.08f) titleTextSize = w * 0.08f;
        
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(mainTextColor);
        titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titlePaint.setTextSize(titleTextSize);

        Paint exifPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        exifPaint.setColor(mainTextColor); 
        exifPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        exifPaint.setTextSize(exifTextSize);

        Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(subTextColor);
        metaPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        metaPaint.setTextSize(metaTextSize);

        Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dividerPaint.setColor(dividerColor);
        dividerPaint.setStrokeWidth(Math.max(1f, w * 0.002f));

        if (!config.isFooterMode) {
            titlePaint.setShadowLayer(6, 0, 2, 0x88000000);
            exifPaint.setShadowLayer(4, 0, 1, 0x88000000);
            metaPaint.setShadowLayer(4, 0, 1, 0x88000000);
        }

        float centerY = startY + (footerHeight / 2f);
        
        Paint.FontMetrics titleFm = titlePaint.getFontMetrics();
        float titleCapHeight = Math.abs(titleFm.ascent);
        
        Paint.FontMetrics exifFm = exifPaint.getFontMetrics();
        float exifCapHeight = Math.abs(exifFm.ascent);
        
        Paint.FontMetrics metaFm = metaPaint.getFontMetrics();
        float metaCapHeight = Math.abs(metaFm.ascent);

        // --- Layout Logic ---

        if (config.align == 1) { 
            // === CENTER ALIGN ===
            titlePaint.setTextAlign(Paint.Align.CENTER);
            exifPaint.setTextAlign(Paint.Align.CENTER);
            metaPaint.setTextAlign(Paint.Align.CENTER);
            
            float spacing = footerHeight * 0.15f;
            float blockH = (config.showLogo ? titleCapHeight : 0) + spacing + exifCapHeight;
            float blockStartY = centerY - (blockH / 2f);
            
            if (config.showLogo) {
                blockStartY += titleCapHeight;
                canvas.drawText(titleText, w / 2f, blockStartY, titlePaint);
            }
            
            String combinedInfo = exifText + (metaText.isEmpty() ? "" : "  •  " + metaText);
            float bottomY = config.showLogo ? blockStartY + spacing + exifCapHeight : centerY + exifCapHeight/2f;
            canvas.drawText(combinedInfo, w / 2f, bottomY, metaPaint);

        } else if (config.align == 2) {
            // === RIGHT ALIGN ===
            float rightX = w - paddingEdge;
            titlePaint.setTextAlign(Paint.Align.RIGHT);
            exifPaint.setTextAlign(Paint.Align.RIGHT);
            metaPaint.setTextAlign(Paint.Align.RIGHT);
            
            if (config.showLogo) canvas.drawText(titleText, rightX, centerY - (containerH * 0.1f), titlePaint);
            canvas.drawText(exifText + " | " + metaText, rightX, centerY + (containerH * 0.25f), metaPaint);

        } else {
            // === LEFT / SPLIT (Default) ===
            float leftX = paddingEdge;
            float rightX = w - paddingEdge;
            
            // 1. Left Title
            titlePaint.setTextAlign(Paint.Align.LEFT);
            if (config.showLogo) {
                float titleY = centerY + (titleCapHeight / 2f) - (titleFm.descent / 2f);
                canvas.drawText(titleText, leftX, titleY, titlePaint);
            }
            
            // 2. Right Data
            exifPaint.setTextAlign(Paint.Align.RIGHT);
            metaPaint.setTextAlign(Paint.Align.RIGHT);
            
            float gap = containerH * 0.12f;
            float rightBlockH = exifCapHeight + gap + metaCapHeight;
            
            float exifY = centerY - (rightBlockH / 2f) + exifCapHeight;
            float metaY = exifY + gap + metaCapHeight * 0.8f;
            
            canvas.drawText(exifText, rightX, exifY, exifPaint);
            canvas.drawText(metaText, rightX, metaY, metaPaint);
            
            // 3. Vertical Divider (Only if Title exists)
            if (config.isFooterMode && config.showLogo) {
                float titleWidth = titlePaint.measureText(titleText);
                float maxRightW = Math.max(exifPaint.measureText(exifText), metaPaint.measureText(metaText));
                float dividerX = leftX + titleWidth + (w * 0.06f);
                
                if (dividerX < (rightX - maxRightW - (w * 0.04f))) {
                    float lineH = containerH * 0.55f;
                    canvas.drawLine(dividerX, centerY - lineH/2f, dividerX, centerY + lineH/2f, dividerPaint);
                }
            }
        }

        return output;
    }
}