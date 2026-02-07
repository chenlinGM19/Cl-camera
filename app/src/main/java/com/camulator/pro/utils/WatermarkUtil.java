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
        // Use max dimension to keep scale consistent across orientations
        int refDim = Math.max(w, h);
        
        // Calculate Footer Height directly from percent (0% - 10%)
        // No minimum floor logic anymore, allowing full control.
        int footerHeight = (int) (refDim * config.heightPercent);
        
        // If height is 0, we can just return the original if it's footer mode (no extra pixels), 
        // or just draw nothing if it's overlay.
        // However, for code simplicity, we proceed. If footerHeight is 0, outputH = h.
        
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
        String titleText = config.customText.isEmpty() ? "Camulator Pro" : config.customText;
        String modelText = android.os.Build.MODEL.toUpperCase();
        
        StringBuilder metaBuilder = new StringBuilder();
        if (config.showDate && !config.dateStr.isEmpty()) metaBuilder.append(config.dateStr);
        
        if (!config.locStr.isEmpty()) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(config.locStr);
        }
        String metaText = metaBuilder.toString();
        String exifText = config.exifInfo;
        if (exifText.isEmpty()) exifText = "RAW";

        // 6. Dynamic Font Sizing (The key to "Aesthetics")
        // Use footerHeight as the container reference.
        float containerH = footerHeight;
        
        // If height is 0, skip text drawing
        if (containerH <= 0) {
            return output;
        }

        float paddingEdge = w * 0.04f; // 4% horizontal padding
        
        // Ratios relative to Footer Height
        float titleTextSize = containerH * 0.32f; 
        float exifTextSize = containerH * 0.22f;
        float metaTextSize = containerH * 0.18f;
        
        // Cap max size relative to Image Width to avoid overflow on narrow phones
        // e.g. if container is very tall but image is narrow
        if (titleTextSize > w * 0.08f) titleTextSize = w * 0.08f;
        
        // Setup Paints
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(mainTextColor);
        titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titlePaint.setTextSize(titleTextSize);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            titlePaint.setLetterSpacing(0.03f);
        }

        Paint exifPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        exifPaint.setColor(mainTextColor); 
        exifPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        exifPaint.setTextSize(exifTextSize);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            exifPaint.setLetterSpacing(0.02f);
        }

        Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(subTextColor);
        metaPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        metaPaint.setTextSize(metaTextSize);

        Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dividerPaint.setColor(dividerColor);
        dividerPaint.setStrokeWidth(Math.max(1f, w * 0.002f)); // Line width

        // Shadows for Overlay mode clarity
        if (!config.isFooterMode) {
            titlePaint.setShadowLayer(6, 0, 2, 0x88000000);
            exifPaint.setShadowLayer(4, 0, 1, 0x88000000);
            metaPaint.setShadowLayer(4, 0, 1, 0x88000000);
        }

        // 7. Calculate Drawing Positions
        
        // Center Y of the footer area
        float centerY = startY + (footerHeight / 2f);
        
        // Get Text Heights for vertical centering corrections
        Paint.FontMetrics titleFm = titlePaint.getFontMetrics();
        float titleCapHeight = Math.abs(titleFm.ascent); // Height from baseline to top
        
        Paint.FontMetrics exifFm = exifPaint.getFontMetrics();
        float exifCapHeight = Math.abs(exifFm.ascent);
        
        Paint.FontMetrics metaFm = metaPaint.getFontMetrics();
        float metaCapHeight = Math.abs(metaFm.ascent);

        // --- Layout Logic ---

        if (config.align == 1) { 
            // === CENTER ALIGN (Minimalist) ===
            titlePaint.setTextAlign(Paint.Align.CENTER);
            exifPaint.setTextAlign(Paint.Align.CENTER);
            metaPaint.setTextAlign(Paint.Align.CENTER);
            
            // Layout: Title Top, Info Bottom
            float spacing = footerHeight * 0.15f;
            float blockH = titleCapHeight + spacing + exifCapHeight;
            float blockStartY = centerY - (blockH / 2f) + titleCapHeight;

            canvas.drawText(titleText, w / 2f, blockStartY, titlePaint);
            
            // Combine Exif and Meta for center look
            String combinedInfo = exifText + "  •  " + metaText;
            canvas.drawText(combinedInfo, w / 2f, blockStartY + spacing + exifCapHeight, metaPaint);

        } else if (config.align == 2) {
            // === RIGHT ALIGN (Clean) ===
            float rightX = w - paddingEdge;
            titlePaint.setTextAlign(Paint.Align.RIGHT);
            exifPaint.setTextAlign(Paint.Align.RIGHT);
            metaPaint.setTextAlign(Paint.Align.RIGHT);
            
            canvas.drawText(titleText, rightX, centerY - (containerH * 0.1f), titlePaint);
            canvas.drawText(exifText + " | " + metaText, rightX, centerY + (containerH * 0.25f), metaPaint);

        } else {
            // === LEFT / SPLIT PROFESSIONAL (Default) ===
            // Left: Logo/Title
            // Right: Exif (Top) / Date+Loc (Bottom)
            
            float leftX = paddingEdge;
            float rightX = w - paddingEdge;
            
            // 1. Draw Left Title (Vertically Centered)
            titlePaint.setTextAlign(Paint.Align.LEFT);
            float titleY = centerY + (titleCapHeight / 2f) - (titleFm.descent / 2f);
            canvas.drawText(titleText, leftX, titleY, titlePaint);
            
            // 2. Draw Right Data (Split Lines)
            exifPaint.setTextAlign(Paint.Align.RIGHT);
            metaPaint.setTextAlign(Paint.Align.RIGHT);
            
            // Calculate vertical positions for two lines
            float gap = containerH * 0.12f; // Gap between Exif and Meta
            float rightBlockH = exifCapHeight + gap + metaCapHeight;
            
            float exifY = centerY - (rightBlockH / 2f) + exifCapHeight;
            float metaY = exifY + gap + metaCapHeight * 0.8f; // Slight adjustment for baseline
            
            canvas.drawText(exifText, rightX, exifY, exifPaint);
            canvas.drawText(metaText, rightX, metaY, metaPaint);
            
            // 3. Vertical Divider
            if (config.isFooterMode) {
                float titleWidth = titlePaint.measureText(titleText);
                float maxRightW = Math.max(exifPaint.measureText(exifText), metaPaint.measureText(metaText));
                
                // Ideal X is somewhat towards the left to give the data section more room
                float dividerX = leftX + titleWidth + (w * 0.06f);
                
                // Ensure divider doesn't hit right text
                if (dividerX < (rightX - maxRightW - (w * 0.04f))) {
                    float lineH = containerH * 0.55f; // Line is 55% height of footer
                    float lineTop = centerY - (lineH / 2f);
                    float lineBottom = centerY + (lineH / 2f);
                    
                    canvas.drawLine(dividerX, lineTop, dividerX, lineBottom, dividerPaint);
                    
                    // Optional: Draw Camera Model Name next to divider if there is space
                    Paint modelPaint = new Paint(metaPaint);
                    modelPaint.setColor(subTextColor);
                    modelPaint.setTextAlign(Paint.Align.LEFT);
                    modelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                    
                    // Scale model text slightly smaller
                    modelPaint.setTextSize(metaTextSize * 0.9f);
                    
                    // Only draw if plenty of space
                    float availableSpace = (rightX - maxRightW) - dividerX;
                    if (availableSpace > w * 0.15f) {
                        canvas.drawText(modelText, dividerX + (w * 0.03f), titleY, modelPaint);
                    }
                }
            }
        }

        return output;
    }
}