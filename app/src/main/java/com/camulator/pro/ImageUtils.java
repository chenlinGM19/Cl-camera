package com.camulator.pro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageUtils {

    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;
            Matrix matrix = new Matrix();
            matrix.postRotate(image.getImageInfo().getRotationDegrees());
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap processImage(Bitmap original, CurvePreset preset, WatermarkConfig wmConfig, boolean cropToSquare) {
        
        Bitmap workingBitmap = original;
        if (cropToSquare) {
            int w = original.getWidth();
            int h = original.getHeight();
            int size = Math.min(w, h);
            int x = (w - size) / 2;
            int y = (h - size) / 2;
            workingBitmap = Bitmap.createBitmap(original, x, y, size, size);
        }

        Bitmap mutable;
        try {
            mutable = workingBitmap.copy(Bitmap.Config.ARGB_8888, true);
        } catch (OutOfMemoryError e) {
            return workingBitmap; 
        }
        
        // Generate Master LUTs
        int[][] luts = generateMasterLUTs(preset);
        
        // Generate Color Matrix for saturation
        float[] colorMatrix = null;
        if (preset.saturation != 0) {
            ColorMatrix cm = new ColorMatrix();
            float satScale = 1.0f + (preset.saturation / 100f);
            if (satScale < 0) satScale = 0;
            cm.setSaturation(satScale);
            colorMatrix = cm.getArray();
        }
        
        applyPreviewEffects(mutable, null, colorMatrix, luts[0], luts[1], luts[2], luts[3]);

        if (wmConfig.enabled) {
            try {
                if (wmConfig.styleFooter) {
                    return addFooterWatermark(mutable, wmConfig);
                } else {
                    Canvas c = new Canvas(mutable);
                    drawOverlayWatermark(c, mutable.getWidth(), mutable.getHeight(), wmConfig);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return mutable;
    }

    private static Bitmap addFooterWatermark(Bitmap src, WatermarkConfig config) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (config.watermarkScale <= 0.001f) return src;
        
        int footerH = (int) (h * config.watermarkScale);
        if (footerH < 20) footerH = 20; 

        Bitmap out;
        try {
            out = Bitmap.createBitmap(w, h + footerH, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return src;
        }
        
        Canvas c = new Canvas(out);
        c.drawColor(config.backgroundColor);
        c.drawBitmap(src, 0, 0, null);
        
        Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pText.setColor(config.textColor);
        
        float fontSizePrimary = footerH * 0.35f;
        pText.setTextSize(fontSizePrimary);
        pText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        
        String mainText = config.showLogo ? config.customText : "CAMULATOR PRO";
        float padding = w * 0.03f;
        float centerY = h + footerH / 2f + fontSizePrimary / 3f;
        
        c.drawText(mainText, padding, centerY, pText);
        
        Paint pMeta = new Paint(Paint.ANTI_ALIAS_FLAG);
        pMeta.setColor(Color.GRAY);
        float fontSizeMeta = footerH * 0.28f;
        pMeta.setTextSize(fontSizeMeta);
        pMeta.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        pMeta.setTextAlign(Paint.Align.RIGHT);
        
        StringBuilder meta = new StringBuilder();
        if (config.showTime) meta.append(new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(new Date()));
        
        // Combine Location Logic
        boolean hasLocation = (config.showDistrict || config.showStreet) && config.placeName != null && !config.placeName.isEmpty();
        
        if (hasLocation) {
            if (meta.length() > 0) meta.append(" | ");
            meta.append(config.placeName);
        }
        if (config.showCoords && config.latLng != null && !config.latLng.isEmpty()) {
            if (meta.length() > 0) meta.append(" | ");
            meta.append(config.latLng);
        }
        String metaStr = meta.toString();
        
        Paint pLine = new Paint();
        pLine.setColor(Color.LTGRAY);
        pLine.setStrokeWidth(Math.max(1f, footerH * 0.02f));
        float lineX = w - padding - pMeta.measureText(metaStr) - padding/2;
        
        if (lineX > padding + pText.measureText(mainText) + padding) {
             c.drawLine(lineX, h + footerH * 0.3f, lineX, h + footerH * 0.7f, pLine);
             c.drawText(metaStr, w - padding, centerY, pMeta);
        } else {
             c.drawText(metaStr, w - padding, centerY, pMeta);
        }
        
        return out;
    }
    
    private static void drawOverlayWatermark(Canvas c, int w, int h, WatermarkConfig config) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(config.textColor);
        p.setShadowLayer(3f, 1f, 1f, Color.parseColor("#99000000"));
        
        float baseSize = h * config.watermarkScale;
        if (baseSize < 10) baseSize = 10;
        p.setTextSize(baseSize);
        
        StringBuilder sb = new StringBuilder();
        if (config.showLogo) sb.append(config.customText);
        if (config.showTime) sb.append("  ").append(new SimpleDateFormat("MM.dd HH:mm", Locale.US).format(new Date()));
        
        String text = sb.toString();
        float tw = p.measureText(text);
        float padding = w * 0.03f;
        
        float x = padding;
        if (config.position == 1) x = (w - tw) / 2;
        if (config.position == 2) x = w - padding - tw;
        
        c.drawText(text, x, h - padding, p);
    }

    public static void applyPreviewEffects(Bitmap bitmap, int[] reusableBuffer, float[] colorMatrix,
                                           int[] lutRGB, int[] lutR, int[] lutG, int[] lutB) {
        if (bitmap == null) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        
        int[] pixels = reusableBuffer;
        if (pixels == null || pixels.length < w * h) {
            pixels = new int[w * h];
        }
        
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        float m0=0,m1=0,m2=0,m4=0, m5=0,m6=0,m7=0,m9=0, m10=0,m11=0,m12=0,m14=0;
        if (colorMatrix != null) {
            m0=colorMatrix[0]; m1=colorMatrix[1]; m2=colorMatrix[2]; m4=colorMatrix[4];
            m5=colorMatrix[5]; m6=colorMatrix[6]; m7=colorMatrix[7]; m9=colorMatrix[9];
            m10=colorMatrix[10]; m11=colorMatrix[11]; m12=colorMatrix[12]; m14=colorMatrix[14];
        }

        int len = w * h;
        for (int i = 0; i < len; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int a = c & 0xFF000000;

            if (colorMatrix != null) {
                float nr = r * m0 + g * m1 + b * m2 + m4;
                float ng = r * m5 + g * m6 + b * m7 + m9;
                float nb = r * m10 + g * m11 + b * m12 + m14;
                r = (nr > 255) ? 255 : (nr < 0) ? 0 : (int) nr;
                g = (ng > 255) ? 255 : (ng < 0) ? 0 : (int) ng;
                b = (nb > 255) ? 255 : (nb < 0) ? 0 : (int) nb;
            }

            // Apply Master LUTs (Curve + Tones + Grading baked in)
            if (lutRGB != null) { r = lutRGB[r]; g = lutRGB[g]; b = lutRGB[b]; }
            if (lutR != null) r = lutR[r];
            if (lutG != null) g = lutG[g];
            if (lutB != null) b = lutB[b];
            
            pixels[i] = a | (r << 16) | (g << 8) | b;
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
    
    public static int[] generateLUT(List<PointF> knots) {
        int[] lut = new int[256];
        if (knots == null || knots.size() < 2) {
            for(int i=0; i<256; i++) lut[i] = i;
            return lut;
        }

        List<PointF> sorted = new ArrayList<>(knots);
        Collections.sort(sorted, (a,b) -> Float.compare(a.x, b.x));
        
        int n = sorted.size();
        float[] x = new float[n];
        float[] y = new float[n];
        for(int i=0; i<n; i++) {
            x[i] = sorted.get(i).x * 255f;
            y[i] = (1f - sorted.get(i).y) * 255f;
        }

        SplineInterpolator spline = new SplineInterpolator(x, y);
        for(int i=0; i<256; i++) {
            lut[i] = (int) Math.max(0, Math.min(255, spline.interpolate(i)));
        }
        return lut;
    }

    public static int[][] generateMasterLUTs(CurvePreset preset) {
        int[] rgb = generateLUT(preset.rgb);
        int[] r = generateLUT(preset.r);
        int[] g = generateLUT(preset.g);
        int[] b = generateLUT(preset.b);
        
        float highlights = preset.highlights / 100f; 
        float shadows = preset.shadows / 100f;
        float whites = preset.whites / 100f;
        float blacks = preset.black / 100f;
        float midtones = preset.midtones / 100f;

        float shHue = preset.shadowHue;
        float shSat = preset.shadowSat / 100f;
        float hlHue = preset.highlightHue;
        float hlSat = preset.highlightSat / 100f;
        
        int[] shRGB = hsvToRgb(shHue, shSat, 1.0f);
        int[] hlRGB = hsvToRgb(hlHue, hlSat, 1.0f);

        for (int i = 0; i < 256; i++) {
            float val = rgb[i] / 255f;
            
            // Tone Curve Adjustments
            if (shadows != 0) {
                 float factor = (1f - val) * (1f - val);
                 val += shadows * factor * 0.5f; 
            }
            if (highlights != 0) {
                 float factor = val * val; 
                 val += highlights * factor * 0.5f;
            }
            if (midtones != 0) {
                 float gamma = 1.0f - (midtones * 0.5f);
                 if (gamma <= 0.1f) gamma = 0.1f;
                 val = (float) Math.pow(val, gamma);
            }
            if (blacks != 0) {
                 float factor = (1f - val);
                 val += blacks * factor * 0.3f;
            }
            if (whites != 0) {
                 float factor = val;
                 val += whites * factor * 0.3f;
            }

            rgb[i] = clamp(val * 255f);
            
            // Color Grading (Split Toning)
            float lum = rgb[i] / 255f; 
            float shWeight = (1.0f - lum) * (1.0f - lum);
            float hlWeight = lum * lum;
            
            float rv = r[i] / 255f;
            rv += (shRGB[0]/255f - 1f) * shWeight * shSat * 0.5f; 
            rv += (shRGB[0]/255f) * shWeight * shSat * 0.2f;      
            rv += (hlRGB[0]/255f) * hlWeight * hlSat * 0.2f;
            r[i] = clamp(rv * 255f);

            float gv = g[i] / 255f;
            gv += (shRGB[1]/255f - 1f) * shWeight * shSat * 0.5f;
            gv += (shRGB[1]/255f) * shWeight * shSat * 0.2f;
            gv += (hlRGB[1]/255f) * hlWeight * hlSat * 0.2f;
            g[i] = clamp(gv * 255f);

            float bv = b[i] / 255f;
            bv += (shRGB[2]/255f - 1f) * shWeight * shSat * 0.5f;
            bv += (shRGB[2]/255f) * shWeight * shSat * 0.2f;
            bv += (hlRGB[2]/255f) * hlWeight * hlSat * 0.2f;
            b[i] = clamp(bv * 255f);
        }
        
        return new int[][] { rgb, r, g, b };
    }

    private static int clamp(float val) {
        return Math.max(0, Math.min(255, Math.round(val)));
    }
    
    private static int[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = v - c;
        float r=0, g=0, b=0;
        if (h < 60) { r=c; g=x; b=0; }
        else if (h < 120) { r=x; g=c; b=0; }
        else if (h < 180) { r=0; g=c; b=x; }
        else if (h < 240) { r=0; g=x; b=c; }
        else if (h < 300) { r=x; g=0; b=c; }
        else { r=c; g=0; b=x; }
        return new int[] { (int)((r+m)*255), (int)((g+m)*255), (int)((b+m)*255) };
    }
    
    public static class WatermarkConfig implements Cloneable {
        public boolean enabled = true;
        public boolean styleFooter = true;
        public int backgroundColor = Color.WHITE;
        public int textColor = Color.BLACK;
        public boolean showLogo = true;
        public String customText = "CAMULATOR";
        public boolean showTime = true;
        public boolean showCoords = false;
        
        // Granular location settings
        public boolean showDistrict = true; // City/District level
        public boolean showStreet = false;  // Street/Thoroughfare level
        
        public String latLng = "";
        public String placeName = "";
        public int position = 0;
        public float watermarkScale = 0.05f; 
        
        @Override
        public WatermarkConfig clone() {
            try { return (WatermarkConfig) super.clone(); } catch (CloneNotSupportedException e) { return new WatermarkConfig(); }
        }
    }

    public static class CurvePreset {
        public String name = "New Preset";
        public List<PointF> rgb = new ArrayList<>();
        public List<PointF> r = new ArrayList<>();
        public List<PointF> g = new ArrayList<>();
        public List<PointF> b = new ArrayList<>();
        public float saturation = 0f;
        
        public float highlights = 0f;
        public float shadows = 0f;
        public float whites = 0f;
        public float black = 0f;
        public float midtones = 0f;
        
        public float shadowHue = 0f;
        public float shadowSat = 0f; 
        public float highlightHue = 0f;
        public float highlightSat = 0f; 

        public CurvePreset() { reset(); }
        public void reset() {
            rgb = defaultPoints(); r = defaultPoints(); g = defaultPoints(); b = defaultPoints(); 
            saturation = 0f;
            highlights = 0f; shadows = 0f; whites = 0f; black = 0f; midtones = 0f;
            shadowHue = 0f; shadowSat = 0f; highlightHue = 0f; highlightSat = 0f;
        }
        private List<PointF> defaultPoints() { List<PointF> p = new ArrayList<>(); p.add(new PointF(0f, 1f)); p.add(new PointF(1f, 0f)); return p; }
        
        public static CurvePreset fromXmp(String xmpContent) {
            CurvePreset preset = new CurvePreset();
            try {
                Matcher nameMatcher = Pattern.compile("<crs:Name>\\s*<rdf:Alt>\\s*<rdf:li[^>]*>(.*?)</rdf:li>", Pattern.DOTALL).matcher(xmpContent);
                if (nameMatcher.find()) preset.name = nameMatcher.group(1).trim();
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
        public String toXmp() { return ""; }
    }

    public static class SplineInterpolator {
        private final float[] x, y, m;
        public SplineInterpolator(float[] x, float[] y) {
            this.x = x; this.y = y;
            int n = x.length;
            float[] d = new float[n - 1];
            float[] m = new float[n];
            for (int i = 0; i < n - 1; i++) {
                float h = x[i + 1] - x[i];
                if (h == 0f) d[i] = 0f; else d[i] = (y[i + 1] - y[i]) / h;
            }
            m[0] = d[0]; m[n - 1] = d[n - 2];
            for (int i = 1; i < n - 1; i++) {
                if (d[i - 1] * d[i] <= 0f) m[i] = 0f;
                else m[i] = (d[i - 1] + d[i]) * 0.5f;
            }
            this.m = m;
        }
        public float interpolate(float val) {
            int n = x.length;
            if (val <= x[0]) return y[0];
            if (val >= x[n - 1]) return y[n - 1];
            int i = 0;
            while (val > x[i + 1]) i++;
            float h = x[i + 1] - x[i];
            float t = (val - x[i]) / h;
            float t2 = t * t, t3 = t2 * t;
            float h00 = 2f * t3 - 3f * t2 + 1f;
            float h10 = t3 - 2f * t2 + t;
            float h01 = -2f * t3 + 3f * t2;
            float h11 = t3 - t2;
            return h00 * y[i] + h10 * h * m[i] + h01 * y[i + 1] + h11 * h * m[i + 1];
        }
    }
}