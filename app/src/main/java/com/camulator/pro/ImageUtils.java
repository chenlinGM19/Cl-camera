package com.camulator.pro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.Log;

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

    // Process image with Saturation Slider support (XMP Saturation maps to -100..100)
    public static Bitmap processImage(Bitmap original, FilterType filterType, float saturationVal,
                                      int[] lutRGB, int[] lutR, int[] lutG, int[] lutB,
                                      WatermarkConfig wmConfig, boolean cropToSquare) {
        
        // 1. Crop if needed (1:1)
        Bitmap workingBitmap = original;
        if (cropToSquare) {
            int w = original.getWidth();
            int h = original.getHeight();
            int size = Math.min(w, h);
            int x = (w - size) / 2;
            int y = (h - size) / 2;
            workingBitmap = Bitmap.createBitmap(original, x, y, size, size);
        }

        // Ensure mutable
        Bitmap mutable = workingBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);
        Paint paint = new Paint();

        // 2. Base Filter
        ColorMatrix cm = getFilterMatrix(filterType);
        
        // 3. Apply XMP Saturation (-100 to 100)
        // 0 = Identity (1.0 scale). -100 = BW (0.0 scale). +100 = High Sat (2.0 scale).
        float satScale = 1.0f + (saturationVal / 100f);
        if (satScale < 0) satScale = 0;
        
        ColorMatrix satCm = new ColorMatrix();
        satCm.setSaturation(satScale);
        
        // Combine Base + Saturation
        cm.postConcat(satCm);
        
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(workingBitmap, 0, 0, paint);
        
        // 4. Apply Curves (Pixel by Pixel)
        if (isCurveActive(lutRGB) || isCurveActive(lutR) || isCurveActive(lutG) || isCurveActive(lutB)) {
            applyCurves(mutable, lutRGB, lutR, lutG, lutB);
        }

        // 5. Apply Watermark
        if (wmConfig.enabled) {
            drawWatermark(canvas, mutable.getWidth(), mutable.getHeight(), wmConfig);
        }

        return mutable;
    }
    
    private static boolean isCurveActive(int[] lut) {
        return lut != null && Math.abs(lut[128] - 128) > 2;
    }

    private static void applyCurves(Bitmap bitmap, int[] rgb, int[] r, int[] g, int[] b) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int alpha = Color.alpha(c);
            int red = Color.red(c);
            int green = Color.green(c);
            int blue = Color.blue(c);

            red = rgb[red];
            green = rgb[green];
            blue = rgb[blue];

            red = r[red];
            green = g[green];
            blue = b[blue];

            pixels[i] = Color.argb(alpha, red, green, blue);
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static ColorMatrix getFilterMatrix(FilterType type) {
        ColorMatrix cm = new ColorMatrix();
        switch (type) {
            case VIVID: cm.setSaturation(1.3f); break;
            case MATTE: 
                cm.set(new float[] { 1,0,0,0,20, 0,1,0,0,20, 0,0,1,0,20, 0,0,0,1,0 });
                break;
            case B_W: cm.setSaturation(0); break;
            case SEPIA:
                cm.set(new float[] { 0.393f, 0.769f, 0.189f, 0, 0, 0.349f, 0.686f, 0.168f, 0, 0, 0.272f, 0.534f, 0.131f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            case CYBERPUNK:
                cm.set(new float[] { 1.2f, 0, 0, 0, 10, 0, 0.9f, 0, 0, 0, 0, 0, 1.4f, 0, 30, 0, 0, 0, 1, 0 });
                break;
            case WARM: cm.setScale(1.1f, 1.05f, 0.9f, 1); break;
            case COOL: cm.setScale(0.9f, 1.0f, 1.15f, 1); break;
            case POLAROID:
                 cm.set(new float[] { 1.1f,0,0,0,0, 0,1.05f,0,0,0, 0,0,0.9f,0,0, 0,0,0,1,0 });
                break;
            case LEICA_M:
                ColorMatrix bw = new ColorMatrix(); bw.setSaturation(0);
                ColorMatrix contrast = new ColorMatrix();
                float scale = 1.3f; float translate = (-.5f * scale + .5f) * 255.f;
                contrast.set(new float[] { scale, 0, 0, 0, translate, 0, scale, 0, 0, translate, 0, 0, scale, 0, translate, 0, 0, 0, 1, 0 });
                cm.setConcat(contrast, bw);
                break;
            case FUJI_SUPERIA:
                 cm.set(new float[] { 1.05f, -0.05f, 0.1f, 0, 0, 0, 1.05f, 0, 0, 0, -0.05f, 0, 1.1f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            case TEAL_ORANGE:
                cm.set(new float[] { 1.2f, -0.1f, 0, 0, 0, -0.05f, 1.0f, -0.05f, 0, 0, 0, -0.2f, 1.4f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            default: break;
        }
        return cm;
    }

    private static void drawWatermark(Canvas canvas, int w, int h, WatermarkConfig config) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.parseColor("#80000000"));
        
        float textSize = h * (config.textSize == 0 ? 0.02f : config.textSize == 1 ? 0.03f : 0.045f);
        textPaint.setTextSize(textSize);

        int padding = (int) (w * 0.04f);
        int bottomMargin = (int) (h * 0.04f);

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

        float startX = padding;
        if (config.position == 1) startX = (w - textPaint.measureText(primaryText)) / 2;
        else if (config.position == 2) startX = w - padding - textPaint.measureText(primaryText);

        float currentY = h - bottomMargin - textSize;

        if (!primaryText.isEmpty()) {
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            canvas.drawText(primaryText, startX, currentY, textPaint);
            currentY += textSize * 1.2f;
        }

        if (!secondaryText.isEmpty()) {
            textPaint.setTextSize(textSize * 0.75f);
            textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
             if (config.position == 1) startX = (w - textPaint.measureText(secondaryText)) / 2;
            else if (config.position == 2) startX = w - padding - textPaint.measureText(secondaryText);
            canvas.drawText(secondaryText, startX, currentY, textPaint);
        }
    }
    
    // --- XMP / Preset Model ---
    
    public static class CurvePreset {
        public String name = "New Preset";
        public List<PointF> rgb = new ArrayList<>();
        public List<PointF> r = new ArrayList<>();
        public List<PointF> g = new ArrayList<>();
        public List<PointF> b = new ArrayList<>();
        public float saturation = 0f; // -100 to 100
        
        public CurvePreset() {
             reset();
        }
        
        public void reset() {
            rgb = defaultPoints();
            r = defaultPoints();
            g = defaultPoints();
            b = defaultPoints();
            saturation = 0f;
        }
        
        private List<PointF> defaultPoints() {
            List<PointF> p = new ArrayList<>();
            p.add(new PointF(0f, 1f));
            p.add(new PointF(1f, 0f));
            return p;
        }
        
        public static CurvePreset fromXmp(String xmpContent) {
            CurvePreset preset = new CurvePreset();
            
            // Extract Name
            Matcher nameMatcher = Pattern.compile("<crs:Name>\\s*<rdf:Alt>\\s*<rdf:li[^>]*>(.*?)</rdf:li>", Pattern.DOTALL).matcher(xmpContent);
            if (nameMatcher.find()) {
                preset.name = nameMatcher.group(1).trim();
            }

            // Extract Saturation
            Matcher satMatcher = Pattern.compile("crs:Saturation=\"([^\"]+)\"").matcher(xmpContent);
            if (satMatcher.find()) {
                try {
                    preset.saturation = Float.parseFloat(satMatcher.group(1));
                } catch (Exception e) {}
            }

            // Extract Curves
            preset.rgb = parseXmpPoints(xmpContent, "ToneCurvePV2012");
            preset.r = parseXmpPoints(xmpContent, "ToneCurvePV2012Red");
            preset.g = parseXmpPoints(xmpContent, "ToneCurvePV2012Green");
            preset.b = parseXmpPoints(xmpContent, "ToneCurvePV2012Blue");
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
                         // Invert Y for CurveView
                         points.add(new PointF(x, 1.0f - y)); 
                     }
                 }
             } catch (Exception e) { e.printStackTrace(); }
             
             if (points.isEmpty()) {
                 points.add(new PointF(0f, 1f));
                 points.add(new PointF(1f, 0f));
             }
             return points;
        }
        
        public String toXmp() {
            StringBuilder sb = new StringBuilder();
            sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n");
            sb.append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
            sb.append("  <rdf:Description rdf:about=\"\" \n");
            sb.append("    xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"\n");
            sb.append("    crs:Version=\"18.1\"\n");
            sb.append("    crs:Saturation=\"").append((int)saturation).append("\"\n");
            sb.append("    crs:HasSettings=\"True\">\n");
            
            sb.append("   <crs:Name>\n    <rdf:Alt>\n     <rdf:li xml:lang=\"x-default\">").append(name).append("</rdf:li>\n    </rdf:Alt>\n   </crs:Name>\n");
            
            appendCurveXmp(sb, "ToneCurvePV2012", rgb);
            appendCurveXmp(sb, "ToneCurvePV2012Red", r);
            appendCurveXmp(sb, "ToneCurvePV2012Green", g);
            appendCurveXmp(sb, "ToneCurvePV2012Blue", b);
            
            sb.append("  </rdf:Description>\n");
            sb.append(" </rdf:RDF>\n");
            sb.append("</x:xmpmeta>");
            return sb.toString();
        }
        
        private void appendCurveXmp(StringBuilder sb, String tagName, List<PointF> points) {
            sb.append("   <crs:").append(tagName).append(">\n");
            sb.append("    <rdf:Seq>\n");
            for(PointF p : points) {
                int x = Math.round(p.x * 255);
                int y = Math.round((1.0f - p.y) * 255); // Invert back
                sb.append("     <rdf:li>").append(x).append(", ").append(y).append("</rdf:li>\n");
            }
            sb.append("    </rdf:Seq>\n");
            sb.append("   </crs:").append(tagName).append(">\n");
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
        public int textSize = 1; 
        public int position = 0; 
    }
}