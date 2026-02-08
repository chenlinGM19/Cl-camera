package com.camulator.pro.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
        public boolean showDistrict = true;
        public boolean showStreet = false;
        
        public String dateStr = "";
        public String gpsStr = ""; // Coordinates
        public String cityText = "";
        public String districtText = "";
        public String streetText = "";
        
        public String exifInfo = ""; // Shutter, ISO, Aperture, Focal Length
        public boolean shouldCrop1to1 = false;
        
        // Custom Logo Image
        public Bitmap logoBitmap = null;
        public float logoCornerRadiusPercent = 0f; // 0.0 to 0.5 (0% to 50%)
    }

    public static Bitmap addWatermark(Bitmap src, WatermarkConfig config) {
        int w = src.getWidth();
        int h = src.getHeight();
        
        // 1. Calculate Layout Dimensions
        int refDim = Math.max(w, h);
        int footerHeight = (int) (refDim * config.heightPercent);
        int outputH = config.isFooterMode ? h + footerHeight : h;
        
        Bitmap output = Bitmap.createBitmap(w, outputH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // 2. Draw Original Image
        canvas.drawBitmap(src, 0, 0, null);
        
        // 3. Setup Colors
        int bgColor = config.isWhiteBg ? 0xFFFFFFFF : 0xFF121212; 
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
        
        StringBuilder metaBuilder = new StringBuilder();
        if (config.showDate && !config.dateStr.isEmpty()) metaBuilder.append(config.dateStr);
        if (config.showGPS && !config.gpsStr.isEmpty()) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(config.gpsStr);
        }
        
        StringBuilder locBuilder = new StringBuilder();
        if (config.showCity && !config.cityText.isEmpty()) locBuilder.append(config.cityText);
        if (config.showDistrict && !config.districtText.isEmpty()) {
            if (locBuilder.length() > 0) locBuilder.append(", ");
            locBuilder.append(config.districtText);
        }
        if (config.showStreet && !config.streetText.isEmpty()) {
            if (locBuilder.length() > 0) locBuilder.append(", ");
            locBuilder.append(config.streetText);
        }
        if (locBuilder.length() > 0) {
            if (metaBuilder.length() > 0) metaBuilder.append("  |  ");
            metaBuilder.append(locBuilder.toString());
        }
        
        String metaText = metaBuilder.toString();
        String exifText = config.exifInfo.isEmpty() ? "RAW" : config.exifInfo;

        // 6. Sizing & Paints
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
        
        Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bitmapPaint.setFilterBitmap(true);

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

        // --- Drawing ---

        if (config.align == 1) { 
            // === CENTER ALIGN ===
            titlePaint.setTextAlign(Paint.Align.CENTER);
            exifPaint.setTextAlign(Paint.Align.CENTER);
            metaPaint.setTextAlign(Paint.Align.CENTER);
            
            float spacing = footerHeight * 0.15f;
            float blockH = (config.showLogo ? titleCapHeight : 0) + spacing + exifCapHeight;
            float blockStartY = centerY - (blockH / 2f);
            
            if (config.showLogo) {
                if (config.logoBitmap != null) {
                    // Draw Image Logo Centered
                    drawLogo(canvas, config.logoBitmap, w/2f, blockStartY + titleCapHeight/2f, titleCapHeight * 1.5f, 1, bitmapPaint, config.logoCornerRadiusPercent);
                    blockStartY += (titleCapHeight * 0.5f); // Adjust spacing slightly for image
                } else {
                    blockStartY += titleCapHeight;
                    canvas.drawText(titleText, w / 2f, blockStartY, titlePaint);
                }
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
            
            if (config.showLogo) {
                if (config.logoBitmap != null) {
                    drawLogo(canvas, config.logoBitmap, rightX, centerY - (containerH * 0.1f), titleCapHeight * 1.5f, 2, bitmapPaint, config.logoCornerRadiusPercent);
                } else {
                    canvas.drawText(titleText, rightX, centerY - (containerH * 0.1f), titlePaint);
                }
            }
            canvas.drawText(exifText + " | " + metaText, rightX, centerY + (containerH * 0.25f), metaPaint);

        } else {
            // === LEFT / SPLIT (Default) ===
            float leftX = paddingEdge;
            float rightX = w - paddingEdge;
            
            // 1. Left Title / Logo
            titlePaint.setTextAlign(Paint.Align.LEFT);
            float logoWidth = 0;
            
            if (config.showLogo) {
                float titleY = centerY + (titleCapHeight / 2f) - (titleFm.descent / 2f);
                if (config.logoBitmap != null) {
                    // Draw Image Logo
                    float targetH = containerH * 0.5f; // 50% of footer height
                    logoWidth = drawLogo(canvas, config.logoBitmap, leftX, centerY, targetH, 0, bitmapPaint, config.logoCornerRadiusPercent);
                } else {
                    canvas.drawText(titleText, leftX, titleY, titlePaint);
                    logoWidth = titlePaint.measureText(titleText);
                }
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
            
            // 3. Vertical Divider - REMOVED per user request
        }
        return output;
    }
    
    // Helper to draw logo maintaining aspect ratio
    // align: 0=Left(anchor left), 1=Center(anchor center), 2=Right(anchor right)
    // cornerPercent: 0.0 (square) to 0.5 (circle/pill)
    // returns width of drawn image
    private static float drawLogo(Canvas canvas, Bitmap logo, float x, float y, float targetHeight, int align, Paint paint, float cornerPercent) {
        float ratio = (float) logo.getWidth() / logo.getHeight();
        float targetWidth = targetHeight * ratio;
        
        float left = x;
        if (align == 1) left = x - targetWidth/2f;
        if (align == 2) left = x - targetWidth;
        
        float top = y - targetHeight/2f;
        
        RectF dst = new RectF(left, top, left+targetWidth, top+targetHeight);
        
        if (cornerPercent > 0) {
            canvas.save();
            Path path = new Path();
            float radius = Math.min(dst.width(), dst.height()) * cornerPercent;
            path.addRoundRect(dst, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            canvas.drawBitmap(logo, null, dst, paint);
            canvas.restore();
        } else {
            canvas.drawBitmap(logo, null, dst, paint);
        }
        
        return targetWidth;
    }
}
