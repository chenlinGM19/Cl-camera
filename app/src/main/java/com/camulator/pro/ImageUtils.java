package com.camulator.pro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageUtils {

    public enum FilterType {
        NONE, VIVID, MATTE, B_W, SEPIA, 
        CYBERPUNK, WARM, COOL, VINTAGE, POLAROID,
        KODAK, FUJI_SUPERIA, LEICA_M, DRAMATIC, PASTEL,
        NOIR, SILVER, GOLDEN, TEAL_ORANGE, FADED,
        HDR, CINEMATIC
    }

    /**
     * Heavy processing for final capture (High Res)
     */
    public static Bitmap processImage(Bitmap original, FilterType filterType, float saturationVal,
                                      int[] lutRGB, int[] lutR, int[] lutG, int[] lutB,
                                      WatermarkConfig wmConfig, boolean cropToSquare) {
        
        // 1. Crop Logic
        Bitmap workingBitmap = original;
        if (cropToSquare) {
            int w = original.getWidth();
            int h = original.getHeight();
            int size = Math.min(w, h);
            int x = (w - size) / 2;
            int y = (h - size) / 2;
            workingBitmap = Bitmap.createBitmap(original, x, y, size, size);
        }

        // Make mutable copy
        Bitmap mutable = workingBitmap.copy(Bitmap.Config.ARGB_8888, true);
        
        // 2. Apply Filters & Curves directly to pixels (CPU intensive but accurate)
        applyFiltersAndCurves(mutable, filterType, saturationVal, lutRGB, lutR, lutG, lutB);

        // 3. Apply Watermark (Canvas drawing)
        if (wmConfig.enabled) {
            if (wmConfig.styleFooter) {
                // Footer logic
                int footerHeight = (int) (mutable.getHeight() * 0.12f);
                int newHeight = mutable.getHeight() + footerHeight;
                Bitmap framed = Bitmap.createBitmap(mutable.getWidth(), newHeight, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(framed);
                c.drawColor(wmConfig.backgroundColor);
                c.drawBitmap(mutable, 0, 0, null);
                drawWatermark(c, mutable.getWidth(), newHeight, wmConfig, mutable.getHeight());
                return framed;
            } else {
                // Overlay logic
                Canvas c = new Canvas(mutable);
                drawWatermark(c, mutable.getWidth(), mutable.getHeight(), wmConfig, -1);
            }
        }

        return mutable;
    }

    /**
     * FAST processing for Real-time Preview. 
     * Modifies the bitmap in-place. Assumes bitmap is already mutable.
     */
    public static void applyPreviewEffects(Bitmap bitmap, FilterType filterType, float saturationVal,
                                           int[] lutRGB, int[] lutR, int[] lutG, int[] lutB) {
        applyFiltersAndCurves(bitmap, filterType, saturationVal, lutRGB, lutR, lutG, lutB);
    }

    private static void applyFiltersAndCurves(Bitmap bitmap, FilterType filterType, float saturationVal,
                                              int[] lutRGB, int[] lutR, int[] lutG, int[] lutB) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        // Pre-calculate ColorMatrix array to avoid object creation inside loop
        float[] colorMatrix = null;
        if (filterType != FilterType.NONE || saturationVal != 0) {
            ColorMatrix cm = getFilterMatrix(filterType);
            if (saturationVal != 0) {
                float satScale = 1.0f + (saturationVal / 100f);
                if (satScale < 0) satScale = 0;
                ColorMatrix satCm = new ColorMatrix();
                satCm.setSaturation(satScale);
                cm.postConcat(satCm);
            }
            colorMatrix = cm.getArray();
        }

        // Loop pixels
        // NOTE: For 12MP images this is slow on Java, but user requested Native Java.
        // For preview (1080p), this is acceptable ~30-50ms.
        
        boolean hasCurves = isCurveActive(lutRGB) || isCurveActive(lutR) || isCurveActive(lutG) || isCurveActive(lutB);
        boolean hasMatrix = colorMatrix != null;

        if (!hasCurves && !hasMatrix) return;

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int a = c & 0xFF000000;

            // 1. Apply Color Matrix
            if (hasMatrix) {
                float nr = r * colorMatrix[0] + g * colorMatrix[1] + b * colorMatrix[2] + colorMatrix[4];
                float ng = r * colorMatrix[5] + g * colorMatrix[6] + b * colorMatrix[7] + colorMatrix[9];
                float nb = r * colorMatrix[10] + g * colorMatrix[11] + b * colorMatrix[12] + colorMatrix[14];
                
                // Clamp
                r = (nr > 255) ? 255 : (nr < 0) ? 0 : (int) nr;
                g = (ng > 255) ? 255 : (ng < 0) ? 0 : (int) ng;
                b = (nb > 255) ? 255 : (nb < 0) ? 0 : (int) nb;
            }

            // 2. Apply Curves
            if (hasCurves) {
                if (lutRGB != null) { r = lutRGB[r]; g = lutRGB[g]; b = lutRGB[b]; }
                if (lutR != null) r = lutR[r];
                if (lutG != null) g = lutG[g];
                if (lutB != null) b = lutB[b];
            }

            pixels[i] = a | (r << 16) | (g << 8) | b;
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
    
    // --- Watermark & Config Logic ---

    private static void drawWatermark(Canvas canvas, int w, int h, WatermarkConfig config, int footerTopY) {
        Paint textPaint = new Paint();
        textPaint.setColor(config.textColor);
        textPaint.setAntiAlias(true);
        
        if (footerTopY == -1) {
            textPaint.setShadowLayer(4f, 2f, 2f, Color.parseColor("#80000000"));
        }
        
        float baseSize = (footerTopY != -1) ? (h - footerTopY) : h;
        // Text scaling
        float scaleFactor = (footerTopY != -1) ? 0.35f : 0.035f; // Default Medium
        if (config.textSize == 0) scaleFactor *= 0.7f;
        if (config.textSize == 2) scaleFactor *= 1.4f;
        
        float textSize = baseSize * scaleFactor;
        textPaint.setTextSize(textSize);

        int padding = (int) (w * 0.04f);
        
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

        float currentY;
        if (footerTopY != -1) {
            // Footer Center Logic
            float footerH = h - footerTopY;
            float totalH = textSize;
            if (!secondaryText.isEmpty()) totalH += textSize * 1.3f;
            currentY = footerTopY + (footerH - totalH) / 2 + textSize * 0.8f; 
        } else {
            // Overlay Logic
            int bottomMargin = (int) (h * 0.05f);
            float totalH = textSize;
            if (!secondaryText.isEmpty()) totalH += textSize * 1.3f;
            currentY = h - bottomMargin - totalH + textSize; 
        }

        float startX;
        // Draw Primary
        if (!primaryText.isEmpty()) {
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            float txtW = textPaint.measureText(primaryText);
            
            if (config.position == 1) startX = (w - txtW) / 2;
            else if (config.position == 2) startX = w - padding - txtW;
            else startX = padding;
            
            canvas.drawText(primaryText, startX, currentY, textPaint);
            currentY += textSize * 1.3f;
        }

        // Draw Secondary
        if (!secondaryText.isEmpty()) {
            textPaint.setTextSize(textSize * 0.75f);
            textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
             float txtW = textPaint.measureText(secondaryText);
            
            if (config.position == 1) startX = (w - txtW) / 2;
            else if (config.position == 2) startX = w - padding - txtW;
            else startX = padding;
            
            canvas.drawText(secondaryText, startX, currentY, textPaint);
        }
    }
    
    private static boolean isCurveActive(int[] lut) {
        return lut != null && Math.abs(lut[128] - 128) > 2;
    }

    private static ColorMatrix getFilterMatrix(FilterType type) {
        ColorMatrix cm = new ColorMatrix();
        switch (type) {
            case VIVID: cm.setSaturation(1.4f); break;
            case MATTE: 
                cm.set(new float[] { 1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0 }); // Reset base
                // Lift blacks logic would require contrast adjustment
                cm.setSaturation(0.9f);
                break;
            case B_W: cm.setSaturation(0); break;
            case SEPIA:
                cm.set(new float[] { 0.393f, 0.769f, 0.189f, 0, 0, 0.349f, 0.686f, 0.168f, 0, 0, 0.272f, 0.534f, 0.131f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            case CYBERPUNK:
                // Boost Magentas and Cyans
                cm.set(new float[] { 1.2f,0,0,0,0, 0,0.9f,0,0,0, 0,0,1.4f,0,0, 0,0,0,1,0 });
                break;
            case WARM: cm.setScale(1.1f, 1.05f, 0.9f, 1); break;
            case COOL: cm.setScale(0.9f, 1.0f, 1.15f, 1); break;
            case POLAROID:
                 cm.set(new float[] { 1.1f,0,0,0,0, 0,1.05f,0,0,0, 0,0,0.9f,0,0, 0,0,0,1,0 });
                break;
            case LEICA_M:
                // High contrast BW
                 cm.setSaturation(0);
                 ColorMatrix c = new ColorMatrix();
                 c.set(new float[] { 1.4f,0,0,0,-30, 0,1.4f,0,0,-30, 0,0,1.4f,0,-30, 0,0,0,1,0 });
                 cm.postConcat(c);
                break;
            case FUJI_SUPERIA:
                 cm.set(new float[] { 1.05f, -0.1f, 0, 0, 0, 0, 1.05f, 0, 0, 0, 0, 0, 1.1f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            case TEAL_ORANGE:
                cm.set(new float[] { 1.1f,0,0,0,0, 0,1.0f,0,0,0, 0,0,0.8f,0,0, 0,0,0,1,0 });
                break;
            default: break;
        }
        return cm;
    }

    public static class CurvePreset {
        public String name = "New Preset";
        public List<PointF> rgb = new ArrayList<>();
        public List<PointF> r = new ArrayList<>();
        public List<PointF> g = new ArrayList<>();
        public List<PointF> b = new ArrayList<>();
        public float saturation = 0f;
        public CurvePreset() { reset(); }
        public void reset() {
            rgb = defaultPoints(); r = defaultPoints(); g = defaultPoints(); b = defaultPoints(); saturation = 0f;
        }
        private List<PointF> defaultPoints() { List<PointF> p = new ArrayList<>(); p.add(new PointF(0f, 1f)); p.add(new PointF(1f, 0f)); return p; }
        
        public static CurvePreset fromXmp(String xmpContent) {
            CurvePreset preset = new CurvePreset();
            try {
                Matcher nameMatcher = Pattern.compile("<crs:Name>\\s*<rdf:Alt>\\s*<rdf:li[^>]*>(.*?)</rdf:li>", Pattern.DOTALL).matcher(xmpContent);
                if (nameMatcher.find()) preset.name = nameMatcher.group(1).trim();
                Matcher satMatcher = Pattern.compile("crs:Saturation=\"([^\"]+)\"").matcher(xmpContent);
                if (satMatcher.find()) { preset.saturation = Float.parseFloat(satMatcher.group(1)); }
                preset.rgb = parseXmpPoints(xmpContent, "ToneCurvePV2012");
                preset.r = parseXmpPoints(xmpContent, "ToneCurvePV2012Red");
                preset.g = parseXmpPoints(xmpContent, "ToneCurvePV2012Green");
                preset.b = parseXmpPoints(xmpContent, "ToneCurvePV2012Blue");
            } catch (Exception e) {}
            return preset;
        }
        private static List<PointF> parseXmpPoints(String content, String tagName) {
             List<PointF> points = new ArrayList<>();
             try {
                 Pattern tagPattern = Pattern.compile("<crs:" + tagName + ">(.*?)</crs:" + tagName + ">", Pattern.DOTALL);
                 Matcher tagMatcher = tagPattern.matcher(content);
                 if (tagMatcher.find()) {
                     String inner = tagMatcher.group(1);
                     Pattern liPattern = Pattern.compile("<rdf:li>\\s*(\\d+),\\s*(\\d+)\\s*</rdf:li>");
                     Matcher liMatcher = liPattern.matcher(inner);
                     while (liMatcher.find()) {
                         float x = Float.parseFloat(liMatcher.group(1)) / 255f;
                         float y = Float.parseFloat(liMatcher.group(2)) / 255f;
                         points.add(new PointF(x, 1.0f - y)); 
                     }
                 }
             } catch (Exception e) {}
             if (points.isEmpty()) { points.add(new PointF(0f, 1f)); points.add(new PointF(1f, 0f)); }
             return points;
        }
        public String toXmp() {
            StringBuilder sb = new StringBuilder();
            sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<rdf:Description rdf:about=\"\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\" crs:Version=\"18.1\" crs:Saturation=\"").append((int)saturation).append("\" crs:HasSettings=\"True\">\n<crs:Name>\n<rdf:Alt>\n<rdf:li xml:lang=\"x-default\">").append(name).append("</rdf:li>\n</rdf:Alt>\n</crs:Name>\n");
            appendCurveXmp(sb, "ToneCurvePV2012", rgb);
            appendCurveXmp(sb, "ToneCurvePV2012Red", r);
            appendCurveXmp(sb, "ToneCurvePV2012Green", g);
            appendCurveXmp(sb, "ToneCurvePV2012Blue", b);
            sb.append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>");
            return sb.toString();
        }
        private void appendCurveXmp(StringBuilder sb, String tagName, List<PointF> points) {
            sb.append("<crs:").append(tagName).append(">\n<rdf:Seq>\n");
            for(PointF p : points) {
                int x = Math.round(p.x * 255); int y = Math.round((1.0f - p.y) * 255);
                sb.append("<rdf:li>").append(x).append(", ").append(y).append("</rdf:li>\n");
            }
            sb.append("</rdf:Seq>\n</crs:").append(tagName).append(">\n");
        }
    }

    public static class WatermarkConfig {
        public boolean enabled = true;
        public boolean styleFooter = true;
        public int backgroundColor = Color.WHITE;
        public int textColor = Color.BLACK;
        public boolean showLogo = true;
        public String customText = "CAMULATOR";
        public boolean showTime = true;
        public boolean showCoords = true;
        public boolean showPlace = false;
        public String latLng = "";
        public String placeName = "";
        public int textSize = 1; 
        public int position = 0; 
    }
}