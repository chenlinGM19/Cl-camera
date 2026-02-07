package com.camulator.pro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.StringReader;
import java.io.StringWriter;
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

    public static Bitmap processImage(Bitmap original, FilterType filterType, float filterIntensity,
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

        // 2. Apply Filter (ColorMatrix) with Intensity
        ColorMatrix targetCm = getFilterMatrix(filterType);
        
        if (filterIntensity < 1.0f) {
            // Blend with Identity Matrix
            ColorMatrix identity = new ColorMatrix(); // Default is identity
            targetCm = mixColorMatrices(identity, targetCm, filterIntensity);
        }
        
        paint.setColorFilter(new ColorMatrixColorFilter(targetCm));
        canvas.drawBitmap(workingBitmap, 0, 0, paint);
        
        // 3. Apply Curves (Pixel by Pixel)
        if (isCurveActive(lutRGB) || isCurveActive(lutR) || isCurveActive(lutG) || isCurveActive(lutB)) {
            applyCurves(mutable, lutRGB, lutR, lutG, lutB);
        }

        // 4. Apply Watermark
        if (wmConfig.enabled) {
            drawWatermark(canvas, mutable.getWidth(), mutable.getHeight(), wmConfig);
        }

        return mutable;
    }
    
    private static ColorMatrix mixColorMatrices(ColorMatrix c1, ColorMatrix c2, float intensity) {
        float[] m1 = c1.getArray();
        float[] m2 = c2.getArray();
        float[] result = new float[20];
        
        for (int i = 0; i < 20; i++) {
            result[i] = m1[i] + (m2[i] - m1[i]) * intensity;
        }
        return new ColorMatrix(result);
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
            case VIVID: cm.setSaturation(1.5f); break;
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
    
    // --- XMP / JSON Preset Model ---
    
    public static class CurvePreset {
        public List<PointF> rgb = new ArrayList<>();
        public List<PointF> r = new ArrayList<>();
        public List<PointF> g = new ArrayList<>();
        public List<PointF> b = new ArrayList<>();
        public String name = "Preset";
        
        public static CurvePreset fromXmp(String xmpContent) {
            CurvePreset preset = new CurvePreset();
            // Basic regex parsing for robustness against namespace variations
            preset.rgb = parseXmpPoints(xmpContent, "ToneCurvePV2012");
            preset.r = parseXmpPoints(xmpContent, "ToneCurvePV2012Red");
            preset.g = parseXmpPoints(xmpContent, "ToneCurvePV2012Green");
            preset.b = parseXmpPoints(xmpContent, "ToneCurvePV2012Blue");
            return preset;
        }
        
        private static List<PointF> parseXmpPoints(String content, String tagName) {
             List<PointF> points = new ArrayList<>();
             // Match content between <crs:tagName> and </crs:tagName>
             // Then find rdf:li items
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
                         // Invert Y because screen Y is 0 at top, math Y is 0 at bottom
                         // Actually CurveView expects 0..1 (0=left/top, 1=right/bottom). 
                         // But Standard Curve: 0,0 is black (bottom-left), 255,255 is white (top-right).
                         // CurveView implementation: Y=0 is Top.
                         // Standard Curve: Input 0 -> Output 0. 
                         // In CurveView Logic: x=0, y=1 (Visual Bottom-Left).
                         // We need to map 0-255 inputs to CurveView 0-1 logic.
                         
                         // X: 0 -> 0.0, 255 -> 1.0
                         // Y: 0 -> 1.0 (Visual Bottom), 255 -> 0.0 (Visual Top)
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
            sb.append("  <rdf:Description rdf:about=\"\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\">\n");
            
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
                // Convert back from CurveView (0..1, inv Y) to XMP (0..255, normal Y)
                int x = Math.round(p.x * 255);
                int y = Math.round((1.0f - p.y) * 255);
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