package com.camulator.pro.processor;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import com.camulator.pro.ui.CurveView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ImageProcessor {

    public static class EditParams {
        public int highlights = 0; // -100 to 100
        public int shadows = 0;
        public int whites = 0;
        public int blacks = 0;
        
        // Color Grading: Hue (0-360), Saturation (0-1)
        public float shadowHue = 0, shadowSat = 0;
        public float midHue = 0, midSat = 0;
        public float highlightHue = 0, highlightSat = 0;
    }

    /**
     * Calculates a Look-Up Table (LUT) using Natural Cubic Spline interpolation.
     * This mimics the smooth, global behavior of Lightroom/Photoshop curves.
     */
    public static float[] calculateCurvePoints(List<PointF> controlPoints, int steps) {
        float[] results = new float[steps];

        // 1. Prepare Points
        List<PointF> points = new ArrayList<>();
        if (controlPoints != null) points.addAll(controlPoints);
        
        // Ensure sorted by X
        Collections.sort(points, (p1, p2) -> Float.compare(p1.x, p2.x));

        // Ensure boundary points exist (0,0) and (1,1) if not present
        if (points.isEmpty() || points.get(0).x > 0.001f) points.add(0, new PointF(0, 0));
        if (points.get(points.size() - 1).x < 0.999f) points.add(new PointF(1, 1));

        int n = points.size() - 1; // number of segments
        float[] x = new float[n + 1];
        float[] y = new float[n + 1];

        for (int i = 0; i <= n; i++) {
            x[i] = points.get(i).x;
            y[i] = points.get(i).y;
        }

        // 2. Natural Cubic Spline Solver
        // Solve for second derivatives (a, b, c, d coefficients)
        // System: a + b(dx) + c(dx)^2 + d(dx)^3
        
        float[] a = new float[n + 1];
        float[] b = new float[n];
        float[] d = new float[n];
        float[] h = new float[n];
        
        for (int i = 0; i < n; i++) {
            a[i] = y[i];
            h[i] = x[i + 1] - x[i];
            // Prevent division by zero if points are stacked
            if (h[i] < 0.0001f) h[i] = 0.0001f; 
        }
        a[n] = y[n];

        float[] alpha = new float[n];
        for (int i = 1; i < n; i++) {
            alpha[i] = (3 * (a[i + 1] - a[i]) / h[i]) - (3 * (a[i] - a[i - 1]) / h[i - 1]);
        }

        float[] c = new float[n + 1];
        float[] l = new float[n + 1];
        float[] mu = new float[n + 1];
        float[] z = new float[n + 1];

        l[0] = 1;
        mu[0] = 0;
        z[0] = 0;

        // Forward elimination
        for (int i = 1; i < n; i++) {
            l[i] = 2 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1];
            mu[i] = h[i] / l[i];
            z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
        }

        l[n] = 1;
        z[n] = 0;
        c[n] = 0;

        // Back substitution
        for (int j = n - 1; j >= 0; j--) {
            c[j] = z[j] - mu[j] * c[j + 1];
            b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3;
            d[j] = (c[j + 1] - c[j]) / (3 * h[j]);
        }

        // 3. Generate LUT
        int currentSegment = 0;
        for (int i = 0; i < steps; i++) {
            float t = (float) i / (steps - 1);
            
            // Find segment
            // Since points are sorted, we can just walk forward
            while (currentSegment < n && t > x[currentSegment + 1]) {
                currentSegment++;
            }
            // Clamp segment
            if (currentSegment >= n) currentSegment = n - 1;

            float dx = t - x[currentSegment];
            
            // Evaluate polynomial: y = a + b*dx + c*dx^2 + d*dx^3
            float value = a[currentSegment] + b[currentSegment] * dx + c[currentSegment] * dx * dx + d[currentSegment] * dx * dx * dx;

            // Clamp output to 0..1 range
            results[i] = Math.max(0f, Math.min(1f, value));
        }

        return results;
    }

    public static int[] calculateLuminanceHistogram(ByteBuffer buffer, int pixelStride) {
        int[] histogram = new int[256];
        if (buffer == null) return histogram;
        buffer.rewind();
        int step = pixelStride * 4; 
        while (buffer.remaining() > 0) {
            int pixel = buffer.get() & 0xFF;
            histogram[pixel]++;
            for(int k=0; k<step-1 && buffer.hasRemaining(); k++) buffer.get();
        }
        return histogram;
    }
    
    // New method for ARGB pixels (from Bitmap)
    public static int[] calculateLuminanceHistogram(int[] pixels) {
        int[] histogram = new int[256];
        if (pixels == null) return histogram;
        // Sampling stride to increase performance on large images
        int stride = Math.max(1, pixels.length / 50000); 
        
        for (int i = 0; i < pixels.length; i += stride) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            // Approx luminance
            int luma = (r * 77 + g * 150 + b * 29) >> 8;
            histogram[luma]++;
        }
        return histogram;
    }

    // --- PROCESSING LOGIC ---

    public static Bitmap applyProcessing(Bitmap src, Map<CurveView.Channel, List<PointF>> curvePoints, EditParams params) {
        if (src == null) return null;
        Bitmap target = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        if (curvePoints == null && params == null) return target;

        int[] masterLut = calculateLUTFromCurve(curvePoints != null ? curvePoints.get(CurveView.Channel.RGB) : null);
        int[] rLut = calculateLUTFromCurve(curvePoints != null ? curvePoints.get(CurveView.Channel.RED) : null);
        int[] gLut = calculateLUTFromCurve(curvePoints != null ? curvePoints.get(CurveView.Channel.GREEN) : null);
        int[] bLut = calculateLUTFromCurve(curvePoints != null ? curvePoints.get(CurveView.Channel.BLUE) : null);
        
        // Prepare Light Adjustment LUT
        int[] lightLut = calculateLightLut(params != null ? params : new EditParams());
        
        // Prepare Color Grading Vectors
        float[] shadowColor = hsvToRgb(params != null ? params.shadowHue : 0, params != null ? params.shadowSat : 0);
        float[] midColor = hsvToRgb(params != null ? params.midHue : 0, params != null ? params.midSat : 0);
        float[] highlightColor = hsvToRgb(params != null ? params.highlightHue : 0, params != null ? params.highlightSat : 0);

        int w = target.getWidth();
        int h = target.getHeight();
        int[] pixels = new int[w * h];
        target.getPixels(pixels, 0, w, 0, 0, w, h);

        boolean hasColorGrade = params != null && (params.shadowSat > 0 || params.midSat > 0 || params.highlightSat > 0);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;

            // 1. Light Adjustment (Applied to input RGB components roughly)
            r = lightLut[r];
            g = lightLut[g];
            b = lightLut[b];

            // 2. Curves (RGB Channels)
            r = rLut[r];
            g = gLut[g];
            b = bLut[b];
            
            // 3. Curves (Master)
            r = masterLut[r];
            g = masterLut[g];
            b = masterLut[b];

            // 4. Color Grading
            if (hasColorGrade) {
                // Approximate luma
                float luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f;
                
                // Weights
                float shadowW = Math.max(0, (1.0f - luma * 2.0f)); // 1.0 at black, 0 at 0.5
                float highlightW = Math.max(0, (luma - 0.5f) * 2.0f); // 0 at 0.5, 1.0 at white
                float midW = 1.0f - shadowW - highlightW;
                
                // Additive color (simplified blending)
                r += (shadowColor[0] * shadowW + midColor[0] * midW + highlightColor[0] * highlightW) * 50;
                g += (shadowColor[1] * shadowW + midColor[1] * midW + highlightColor[1] * highlightW) * 50;
                b += (shadowColor[2] * shadowW + midColor[2] * midW + highlightColor[2] * highlightW) * 50;
                
                r = clamp(r);
                g = clamp(g);
                b = clamp(b);
            }

            pixels[i] = (0xFF000000) | (r << 16) | (g << 8) | b;
        }

        target.setPixels(pixels, 0, w, 0, 0, w, h);
        return target;
    }

    private static int[] calculateLUTFromCurve(List<PointF> points) {
        // Default Identity LUT if points are missing
        if (points == null || points.size() < 2) {
             int[] lut = new int[256];
             for(int i=0; i<256; i++) lut[i] = i;
             return lut;
        }

        // High resolution LUT for calculation, mapped to 256
        float[] curve = calculateCurvePoints(points, 256);
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = clamp((int)(curve[i] * 255f));
        }
        return lut;
    }
    
    // Generates a LUT for H/S/W/B adjustments
    private static int[] calculateLightLut(EditParams p) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float val = i / 255f;
            
            // Shadows: Affects bottom 50%, strongest at 25%
            if (p.shadows != 0) {
                float weight = (float) Math.exp(-Math.pow(val - 0.25, 2) / 0.05);
                val += (p.shadows / 200f) * weight;
            }
            
            // Highlights: Affects top 50%, strongest at 75%
            if (p.highlights != 0) {
                float weight = (float) Math.exp(-Math.pow(val - 0.75, 2) / 0.05);
                val += (p.highlights / 200f) * weight;
            }
            
            // Blacks: Affects darks linearly
            if (p.blacks != 0) {
                val += (p.blacks / 255f) * (1 - val) * (1 - val);
            }

            // Whites: Affects brights linearly
            if (p.whites != 0) {
                val += (p.whites / 255f) * val * val;
            }
            
            lut[i] = clamp((int)(val * 255));
        }
        return lut;
    }

    private static float[] hsvToRgb(float hue, float sat) {
        // Simple helper, return RGB offsets (-1 to 1 range roughly, scaled later)
        if (sat == 0) return new float[]{0,0,0};
        
        float h = hue / 60f;
        float c = sat; // Chroma
        float x = c * (1 - Math.abs(h % 2 - 1));
        
        float r=0, g=0, b=0;
        if(0 <= h && h < 1){ r=c; g=x; b=0; }
        else if(1 <= h && h < 2){ r=x; g=c; b=0; }
        else if(2 <= h && h < 3){ r=0; g=c; b=x; }
        else if(3 <= h && h < 4){ r=0; g=x; b=c; }
        else if(4 <= h && h < 5){ r=x; g=0; b=c; }
        else if(5 <= h && h < 6){ r=c; g=0; b=x; }
        
        // We return signed values where the "Color" pushes towards that tint
        return new float[]{r, g, b};
    }

    private static int clamp(int val) {
        return (val < 0) ? 0 : (val > 255) ? 255 : val;
    }
}