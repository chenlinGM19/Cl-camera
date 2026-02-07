package com.camulator.pro.processor;

import android.graphics.Bitmap;
import android.graphics.PointF;
import com.camulator.pro.ui.CurveView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ImageProcessor {

    /**
     * Generates a normalized curve (0.0 to 1.0) based on control points.
     */
    public static float[] calculateCurvePoints(List<PointF> controlPoints, int steps) {
        float[] results = new float[steps];

        if (controlPoints == null || controlPoints.isEmpty()) {
            // Linear fallback
            for (int i = 0; i < steps; i++) results[i] = (float) i / (steps - 1);
            return results;
        }

        // 1. Prepare and Sort Points
        List<PointF> points = new ArrayList<>(controlPoints);
        Collections.sort(points, (p1, p2) -> Float.compare(p1.x, p2.x));

        // Ensure endpoints exist
        if (points.isEmpty() || points.get(0).x > 0) points.add(0, new PointF(0, 0));
        if (points.get(points.size() - 1).x < 1) points.add(new PointF(1, 1));

        int n = points.size();
        float[] x = new float[n];
        float[] y = new float[n];

        for (int i = 0; i < n; i++) {
            x[i] = points.get(i).x;
            y[i] = points.get(i).y;
        }

        // 2. Compute Monotone Cubic Spline Tangents
        float[] m = computeMonotoneTangents(x, y);

        // 3. Interpolate for every step
        int currentSegment = 0;
        for (int i = 0; i < steps; i++) {
            float inputX = (float) i / (steps - 1);

            // Find segment
            while (currentSegment < n - 1 && inputX > x[currentSegment + 1]) {
                currentSegment++;
            }

            float outputY;
            if (currentSegment >= n - 1) {
                outputY = y[n - 1];
            } else {
                float x1 = x[currentSegment];
                float x2 = x[currentSegment + 1];
                float y1 = y[currentSegment];
                float y2 = y[currentSegment + 1];
                float m1 = m[currentSegment];
                float m2 = m[currentSegment + 1];

                float h = x2 - x1;
                if (h == 0) {
                    outputY = y1;
                } else {
                    float t = (inputX - x1) / h;
                    float t2 = t * t;
                    float t3 = t2 * t;

                    // Hermite basis functions
                    float h00 = 2 * t3 - 3 * t2 + 1;
                    float h10 = t3 - 2 * t2 + t;
                    float h01 = -2 * t3 + 3 * t2;
                    float h11 = t3 - t2;

                    outputY = h00 * y1 + h10 * h * m1 + h01 * y2 + h11 * h * m2;
                }
            }
            
            // Clamp to 0..1 to prevent integer overflow later
            results[i] = Math.max(0f, Math.min(1f, outputY));
        }

        return results;
    }

    private static float[] computeMonotoneTangents(float[] x, float[] y) {
        int n = x.length;
        float[] d = new float[n - 1];
        float[] m = new float[n];

        for (int i = 0; i < n - 1; i++) {
            float h = x[i + 1] - x[i];
            if (h == 0) d[i] = 0;
            else d[i] = (y[i + 1] - y[i]) / h;
        }

        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0) {
                m[i] = 0;
            } else {
                m[i] = (d[i - 1] + d[i]) / 2.0f;
            }
        }

        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) {
                m[i] = 0;
                m[i + 1] = 0;
            } else {
                float a = m[i] / d[i];
                float b = m[i + 1] / d[i];
                if (a < 0 || b < 0) {
                   // Keep fallback
                } else {
                    float dist = a * a + b * b;
                    if (dist > 9) {
                        float tau = 3.0f / (float) Math.sqrt(dist);
                        m[i] = tau * a * d[i];
                        m[i + 1] = tau * b * d[i];
                    }
                }
            }
        }
        return m;
    }

    public static int[] calculateCurveLUT(List<PointF> controlPoints) {
        float[] curve = calculateCurvePoints(controlPoints, 256);
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = (int) (curve[i] * 255f);
            // Double check bounds just in case
            if (lut[i] < 0) lut[i] = 0;
            if (lut[i] > 255) lut[i] = 255;
        }
        return lut;
    }

    /**
     * Calculates a 256-bin histogram from the Y-plane (Luminance) of a YUV image.
     * Fast enough for real-time analysis.
     */
    public static int[] calculateLuminanceHistogram(ByteBuffer buffer, int pixelStride) {
        int[] histogram = new int[256];
        if (buffer == null) return histogram;

        // Rewind buffer to ensure we read from start
        buffer.rewind();
        
        // We can sub-sample for speed if needed, e.g., read every 4th pixel
        // step = pixelStride * 4
        int step = pixelStride * 4; 
        
        while (buffer.remaining() > 0) {
            int pixel = buffer.get() & 0xFF; // Unsigned byte
            histogram[pixel]++;
            
            // Skip bytes if stepping
            if (buffer.remaining() >= step - 1) {
                // simple skip loop is safer for direct buffers than position setting
                for(int k=0; k<step-1 && buffer.hasRemaining(); k++) buffer.get();
            }
        }
        return histogram;
    }

    /**
     * Applies curves to a Bitmap in-place if mutable, otherwise creates copy.
     * Returns the processed bitmap (which might be the same object as src).
     */
    public static Bitmap applyCurves(Bitmap src, Map<CurveView.Channel, List<PointF>> allPoints) {
        if (src == null) return null;
        if (allPoints == null) return src;

        // Optimization: Use in-place modification if bitmap is mutable
        Bitmap target = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        if (target == null) return src; // Should not happen

        int width = target.getWidth();
        int height = target.getHeight();

        int[] masterLut = calculateCurveLUT(allPoints.get(CurveView.Channel.RGB));
        int[] redLut = calculateCurveLUT(allPoints.get(CurveView.Channel.RED));
        int[] greenLut = calculateCurveLUT(allPoints.get(CurveView.Channel.GREEN));
        int[] blueLut = calculateCurveLUT(allPoints.get(CurveView.Channel.BLUE));
        
        // OPTIMIZATION: Combine Channel LUTs with Master LUT beforehand
        // Final Value = MasterLUT[ ChannelLUT[ Input ] ]
        int[] finalRLut = new int[256];
        int[] finalGLut = new int[256];
        int[] finalBLut = new int[256];
        
        for(int i=0; i<256; i++) {
            // Apply individual channel first, then master
            // Safety check for array index out of bounds
            int rVal = redLut[i];
            if(rVal < 0) rVal = 0; if(rVal > 255) rVal = 255;
            finalRLut[i] = masterLut[rVal];

            int gVal = greenLut[i];
            if(gVal < 0) gVal = 0; if(gVal > 255) gVal = 255;
            finalGLut[i] = masterLut[gVal];
            
            int bVal = blueLut[i];
            if(bVal < 0) bVal = 0; if(bVal > 255) bVal = 255;
            finalBLut[i] = masterLut[bVal];
        }
        
        int[] pixels = new int[width * height];
        target.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            // Alpha does not change
            int a = p & 0xFF000000;
            
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            
            // Lookup combined
            r = finalRLut[r];
            g = finalGLut[g];
            b = finalBLut[b];
            
            pixels[i] = a | (r << 16) | (g << 8) | b;
        }

        target.setPixels(pixels, 0, width, 0, 0, width, height);
        return target;
    }
}
