package com.camulator.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CurveView extends View {

    public enum Channel { RGB, RED, GREEN, BLUE }
    
    public interface OnCurveChangeListener {
        void onCurveChanged();
    }

    private Channel currentChannel = Channel.RGB;
    private Paint linePaint, gridPaint, pointPaint, borderPaint;
    private OnCurveChangeListener listener;
    
    // Store points for each channel
    private List<PointF> pointsRGB, pointsR, pointsG, pointsB;
    private int activePointIndex = -1;

    public CurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public void setOnCurveChangeListener(OnCurveChangeListener listener) {
        this.listener = listener;
    }

    private void init() {
        linePaint = new Paint();
        linePaint.setStrokeWidth(5f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setAntiAlias(true);

        pointPaint = new Paint();
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#44FFFFFF"));
        gridPaint.setStrokeWidth(2f);
        
        borderPaint = new Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);

        resetCurves();
    }
    
    public void resetCurves() {
        pointsRGB = createDefaultPoints();
        pointsR = createDefaultPoints();
        pointsG = createDefaultPoints();
        pointsB = createDefaultPoints();
        invalidate();
        notifyListener();
    }
    
    private void notifyListener() {
        if (listener != null) listener.onCurveChanged();
    }
    
    private List<PointF> createDefaultPoints() {
        List<PointF> list = new ArrayList<>();
        list.add(new PointF(0f, 1f)); // 0,0 visual
        list.add(new PointF(1f, 0f)); // 1,1 visual
        return list;
    }

    public void setChannel(Channel channel) {
        this.currentChannel = channel;
        invalidate();
    }

    private List<PointF> getCurrentPoints() {
        switch (currentChannel) {
            case RED: return pointsR;
            case GREEN: return pointsG;
            case BLUE: return pointsB;
            default: return pointsRGB;
        }
    }
    
    // -- Import/Export Accessors --
    public List<PointF> getPoints(Channel c) {
        switch (c) {
            case RED: return new ArrayList<>(pointsR);
            case GREEN: return new ArrayList<>(pointsG);
            case BLUE: return new ArrayList<>(pointsB);
            default: return new ArrayList<>(pointsRGB);
        }
    }
    
    public void setPoints(Channel c, List<PointF> pts) {
        if (pts == null || pts.size() < 2) pts = createDefaultPoints();
        switch (c) {
            case RED: pointsR = pts; break;
            case GREEN: pointsG = pts; break;
            case BLUE: pointsB = pts; break;
            default: pointsRGB = pts; break;
        }
        invalidate();
        notifyListener();
    }
    // -----------------------------

    // Getters for Image Processing
    public int[] getLutRGB() { return generateLUT(pointsRGB); }
    public int[] getLutR() { return generateLUT(pointsR); }
    public int[] getLutG() { return generateLUT(pointsG); }
    public int[] getLutB() { return generateLUT(pointsB); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        // Draw Grid (4x4)
        for (int i=1; i<4; i++) {
            canvas.drawLine(w*i/4, 0, w*i/4, h, gridPaint);
            canvas.drawLine(0, h*i/4, w, h*i/4, gridPaint);
        }
        canvas.drawRect(0, 0, w, h, borderPaint);

        // Set Paint Color based on Channel
        switch (currentChannel) {
            case RED: linePaint.setColor(Color.RED); break;
            case GREEN: linePaint.setColor(Color.GREEN); break;
            case BLUE: linePaint.setColor(Color.BLUE); break;
            default: linePaint.setColor(Color.WHITE); break;
        }

        List<PointF> points = getCurrentPoints();
        
        // Sort points by X to ensure function validity
        Collections.sort(points, (o1, o2) -> Float.compare(o1.x, o2.x));

        // Draw Spline
        if (points.size() >= 2) {
            Path path = new Path();
            // Generate LUT for smooth drawing
            int[] lut = generateLUT(points);
            path.moveTo(0, h - (lut[0] / 255f * h));
            
            for (int i = 0; i < 256; i+=2) {
                float x = (i / 255f) * w;
                float y = h - (lut[i] / 255f * h);
                path.lineTo(x, y);
            }
            canvas.drawPath(path, linePaint);
        }

        // Draw Control Points
        for (PointF p : points) {
            // Visual Y is inverted from Math Y
            canvas.drawCircle(p.x * w, p.y * h, 18f, pointPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float w = getWidth();
        float h = getHeight();

        // Normalize (0..1)
        float nx = Math.max(0, Math.min(1, x / w));
        float ny = Math.max(0, Math.min(1, y / h));

        List<PointF> points = getCurrentPoints();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touching existing point
                float minDesc = 0.08f; 
                activePointIndex = -1;
                for (int i = 0; i < points.size(); i++) {
                    PointF p = points.get(i);
                    // dist
                    if (Math.hypot(p.x - nx, p.y - ny) < minDesc) {
                        activePointIndex = i;
                        break;
                    }
                }
                
                // If not touching existing, ADD new point
                if (activePointIndex == -1) {
                    points.add(new PointF(nx, ny));
                    activePointIndex = points.size() - 1;
                    invalidate();
                }
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    // Clamp start/end X
                    if (p.x == 0 && activePointIndex == 0) p.x = 0; 
                    else if (p.x == 1 && activePointIndex == points.size()-1) p.x = 1; 
                    else p.x = nx;
                    
                    p.y = ny;
                    invalidate();
                    // Don't notify on every move event to avoid lag, wait for up? 
                    // No, "real-time preview" usually demands it.
                    // We can throttle in Activity if needed.
                    notifyListener();
                }
                break;
                
            case MotionEvent.ACTION_UP:
                activePointIndex = -1;
                notifyListener();
                break;
        }
        return true;
    }
    
    // Monotone Cubic Spline Interpolation to generate LUT (0-255)
    private int[] generateLUT(List<PointF> knots) {
        int[] lut = new int[256];
        if (knots.size() < 2) return lut;

        int n = knots.size();
        float[] x = new float[n];
        float[] y = new float[n];
        
        // Convert visual Y (0 top) to math Y (0 bottom) -> y = 1 - p.y
        for(int i=0; i<n; i++) {
            x[i] = knots.get(i).x * 255f;
            y[i] = (1f - knots.get(i).y) * 255f;
        }
        
        // Interpolate for every value 0..255
        for (int i = 0; i < 256; i++) {
            lut[i] = (int) Math.max(0, Math.min(255, interpolate(i, x, y)));
        }
        return lut;
    }

    private float interpolate(float val, float[] x, float[] y) {
        int n = x.length;
        if (val <= x[0]) return y[0];
        if (val >= x[n-1]) return y[n-1];
        
        // Find segment
        int i = 0;
        while (val > x[i+1]) i++;
        
        float t = (val - x[i]) / (x[i+1] - x[i]);
        return y[i] + t * (y[i+1] - y[i]); 
    }
}