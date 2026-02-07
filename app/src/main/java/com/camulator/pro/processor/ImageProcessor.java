package com.camulator.pro.processor;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import java.util.List;

public class ImageProcessor {

    // Simulates a Monotone Cubic Hermite Spline calculation for lookup table
    public static int[] calculateCurveLUT(List<PointF> controlPoints) {
        int[] lut = new int[256];
        // In a real implementation, interpolate between controlPoints
        // For prototype, we assume Linear between points or default identity
        if (controlPoints == null || controlPoints.size() < 2) {
            for (int i = 0; i < 256; i++) lut[i] = i;
            return lut;
        }
        
        // Very basic interpolation mapping 0..1 coordinates to 0..255
        for (int i = 0; i < 256; i++) {
            float x = i / 255f;
            // Find segment
            PointF p1 = controlPoints.get(0);
            PointF p2 = controlPoints.get(controlPoints.size()-1);
            
            for (int j = 0; j < controlPoints.size() - 1; j++) {
                if (x >= controlPoints.get(j).x && x <= controlPoints.get(j+1).x) {
                    p1 = controlPoints.get(j);
                    p2 = controlPoints.get(j+1);
                    break;
                }
            }
            
            float t = (x - p1.x) / (p2.x - p1.x);
            // Linear interp (y is inverted in UI, 0 is top, so 1-y is value)
            float valY = (1 - p1.y) * (1-t) + (1 - p2.y) * t;
            lut[i] = (int) Math.min(255, Math.max(0, valY * 255));
        }
        
        return lut;
    }

    public static Bitmap applyCurves(Bitmap src, List<PointF> controlPoints) {
        int width = src.getWidth();
        int height = src.getHeight();
        Bitmap dest = Bitmap.createBitmap(width, height, src.getConfig());

        int[] lut = calculateCurveLUT(controlPoints);
        
        int[] pixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            
            r = lut[r];
            g = lut[g];
            b = lut[b];
            
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        dest.setPixels(pixels, 0, width, 0, 0, width, height);
        return dest;
    }
}