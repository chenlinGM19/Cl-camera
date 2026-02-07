package com.camulator.pro.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.camulator.pro.processor.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CurveView extends View {

    public enum Channel {
        RGB, RED, GREEN, BLUE
    }

    // Paints
    private Paint gridPaint;
    private Paint subGridPaint;
    private Paint guidePaint;
    private Paint curvePaint;
    private Paint fillPaint;
    private Paint pointPaint;
    private Paint pointStrokePaint;
    private Paint textPaint;
    private Paint textBgPaint;
    private Paint crosshairPaint;
    private Paint histogramPaint;

    // State
    private Channel activeChannel = Channel.RGB;
    private Map<Channel, List<PointF>> channelPoints = new HashMap<>();
    private OnCurveChangeListener listener;
    private int[] histogramData = new int[256];
    private Path histogramPath = new Path();
    
    // Interaction & Layout
    private int activePointIndex = -1;
    private GestureDetector gestureDetector;
    private RectF graphRect = new RectF();
    
    // Configurable dimensions
    private float paddingPx; 
    private float touchThresholdPx; 
    
    // Caching
    private Path curvePath = new Path();
    private Path fillPath = new Path();
    private float[] cachedCurve = new float[256];

    public interface OnCurveChangeListener {
        void onChange();
    }

    public CurveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // Convert dp to px for consistent touch area across devices
        paddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
        touchThresholdPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());

        // 1. Paints Configuration
        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#505050"));
        gridPaint.setStrokeWidth(2f);

        subGridPaint = new Paint();
        subGridPaint.setColor(Color.parseColor("#303030"));
        subGridPaint.setStrokeWidth(1f);

        guidePaint = new Paint();
        guidePaint.setColor(Color.parseColor("#404040"));
        guidePaint.setStrokeWidth(2f);
        guidePaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        guidePaint.setAntiAlias(true);

        curvePaint = new Paint();
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, getResources().getDisplayMetrics()));
        curvePaint.setAntiAlias(true);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        pointPaint = new Paint();
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        pointStrokePaint = new Paint();
        pointStrokePaint.setColor(Color.BLACK);
        pointStrokePaint.setStyle(Paint.Style.STROKE);
        pointStrokePaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics()));
        pointStrokePaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        textBgPaint = new Paint();
        textBgPaint.setColor(0xCC000000);
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setAntiAlias(true);
        textBgPaint.setPathEffect(new CornerPathEffect(8));
        
        crosshairPaint = new Paint();
        crosshairPaint.setColor(0x88FFFFFF);
        crosshairPaint.setStrokeWidth(2f);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{15, 15}, 0));
        
        histogramPaint = new Paint();
        histogramPaint.setStyle(Paint.Style.FILL);
        histogramPaint.setColor(0x50AAAAAA); // Semi-transparent grey/white
        histogramPaint.setAntiAlias(true);
        histogramPaint.setPathEffect(new CornerPathEffect(4)); 

        // 2. Data Initialization
        resetChannel(Channel.RGB);
        resetChannel(Channel.RED);
        resetChannel(Channel.GREEN);
        resetChannel(Channel.BLUE);

        // 3. Gesture Detector for Double Tap (Delete/Reset)
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                handleDoubleTap(e.getX(), e.getY());
                return true;
            }
            
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });
        
        updateCurvePaths();
    }

    public void setHistogramData(int[] data) {
        if (data == null || data.length != 256) return;
        this.histogramData = data;
        postInvalidate();
    }

    private void resetChannel(Channel c) {
        List<PointF> points = new ArrayList<>();
        points.add(new PointF(0f, 0f));
        points.add(new PointF(1f, 1f));
        channelPoints.put(c, points);
    }

    public void setActiveChannel(Channel channel) {
        this.activeChannel = channel;
        updateCurvePaths();
        invalidate();
    }

    public void setOnCurveChangeListener(OnCurveChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Returns a DEEP COPY of the current control points.
     * Essential for thread safety when processing images in background
     * while the user might still be interacting with the view.
     */
    public Map<Channel, List<PointF>> getControlPointsCopy() {
        Map<Channel, List<PointF>> copy = new HashMap<>();
        for (Map.Entry<Channel, List<PointF>> entry : channelPoints.entrySet()) {
            List<PointF> pointList = new ArrayList<>();
            for (PointF p : entry.getValue()) {
                pointList.add(new PointF(p.x, p.y));
            }
            copy.put(entry.getKey(), pointList);
        }
        return copy;
    }
    
    // Kept for internal use or read-only access
    public Map<Channel, List<PointF>> getAllControlPoints() {
        return channelPoints;
    }

    private void updateCurvePaths() {
        List<PointF> points = channelPoints.get(activeChannel);
        // Calculate the math (0..1)
        cachedCurve = ImageProcessor.calculateCurvePoints(points, 256);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Define graph area with padding
        graphRect.set(paddingPx, paddingPx, w - paddingPx, h - paddingPx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        float left = graphRect.left;
        float top = graphRect.top;
        float right = graphRect.right;
        float bottom = graphRect.bottom;
        float width = graphRect.width();
        float height = graphRect.height();
        
        // 0. Draw Histogram
        drawHistogram(canvas, left, top, right, bottom, width, height);

        // 1. Draw Grid (4x4)
        // Sub-grid (25% lines)
        for (int i = 1; i < 4; i++) {
            float x = left + (i * width / 4f);
            canvas.drawLine(x, top, x, bottom, gridPaint);
            float y = top + (i * height / 4f);
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        // Border
        canvas.drawRect(graphRect, gridPaint);

        // 2. Diagonal Guide (Base Linear Line)
        canvas.drawLine(left, bottom, right, top, guidePaint);

        // 3. Setup Colors
        int mainColor = 0xFFFFFFFF;
        int dimColor = 0x20FFFFFF;

        switch (activeChannel) {
            case RED:
                mainColor = 0xFFFF5252;
                dimColor = 0x30FF5252;
                break;
            case GREEN:
                mainColor = 0xFF69F0AE;
                dimColor = 0x3069F0AE;
                break;
            case BLUE:
                mainColor = 0xFF448AFF;
                dimColor = 0x30448AFF;
                break;
        }

        curvePaint.setColor(mainColor);
        fillPaint.setShader(new LinearGradient(0, top, 0, bottom, dimColor, 0x00000000, Shader.TileMode.CLAMP));

        // 4. Construct Paths
        curvePath.reset();
        fillPath.reset();
        fillPath.moveTo(left, bottom);

        for (int i = 0; i < 256; i++) {
            float x = left + (i / 255f) * width;
            float val = cachedCurve[i]; // 0..1
            float y = bottom - (val * height);

            if (i == 0) {
                curvePath.moveTo(x, y);
                fillPath.lineTo(x, y);
            } else {
                curvePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }

        fillPath.lineTo(right, bottom);
        fillPath.close();

        // 5. Draw Curve & Fill
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(curvePath, curvePaint);

        // 6. Draw Control Points & Interaction UI
        List<PointF> points = channelPoints.get(activeChannel);
        if (points != null) {
            for (int i = 0; i < points.size(); i++) {
                PointF p = points.get(i);
                float px = left + (p.x * width);
                float py = bottom - (p.y * height);

                boolean isActive = (i == activePointIndex);
                float radius = isActive ? 24f : 16f;

                // Draw Crosshair for active point
                if (isActive) {
                    canvas.drawLine(px, top, px, bottom, crosshairPaint);
                    canvas.drawLine(left, py, right, py, crosshairPaint);
                }

                // Draw Point
                canvas.drawCircle(px, py, radius, pointPaint);
                pointStrokePaint.setStrokeWidth(isActive ? 4f : 2f);
                canvas.drawCircle(px, py, radius, pointStrokePaint);

                // Draw Tooltip
                if (isActive) {
                    drawTooltip(canvas, p, px, py);
                }
            }
        }
    }
    
    private void drawHistogram(Canvas canvas, float left, float top, float right, float bottom, float width, float height) {
        if (histogramData == null) return;
        
        int max = 1;
        for (int val : histogramData) {
            if (val > max) max = val;
        }
        
        histogramPath.reset();
        histogramPath.moveTo(left, bottom);
        
        for (int i = 0; i < 256; i++) {
            float x = left + (i / 255f) * width;
            float hVal = (float) histogramData[i] / max;
            
            // Logarithmic boosting for better visibility of dark details
            if (hVal > 0) {
                hVal = (float) (Math.log10(1 + 9 * hVal)); 
            }
            
            float y = bottom - (hVal * height * 0.85f); // 85% height max
            histogramPath.lineTo(x, y);
        }
        
        histogramPath.lineTo(right, bottom);
        histogramPath.close();
        
        canvas.drawPath(histogramPath, histogramPaint);
    }

    private void drawTooltip(Canvas canvas, PointF p, float px, float py) {
        // Formatted nicely
        String text = String.format(Locale.US, "In:%3d  Out:%3d", (int)(p.x * 255), (int)(p.y * 255));
        
        float textW = textPaint.measureText(text);
        float textH = textPaint.getTextSize();
        float pad = 20f;
        
        float boxW = textW + pad * 2;
        float boxH = textH + pad * 1.5f;
        
        // Position: Top Left corner of the graph by default to avoid covering the finger/curve
        // Or floating above finger. Let's do floating but offset significantly.
        
        float tx = px - boxW / 2;
        float ty = py - 120f; 

        // Boundary checks
        if (ty < 0) ty = py + 120f; // If too close to top, move below
        if (tx < 0) tx = pad;
        if (tx + boxW > getWidth()) tx = getWidth() - boxW - pad;

        RectF bg = new RectF(tx, ty - boxH, tx + boxW, ty);
        canvas.drawRect(bg, textBgPaint);
        
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = bg.centerY() - (fm.descent + fm.ascent) / 2;
        canvas.drawText(text, bg.centerX() - textW / 2, textY, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 1. Double Tap Handling
        if (gestureDetector.onTouchEvent(event)) {
            return true;
        }

        float x = event.getX();
        float y = event.getY();

        float width = graphRect.width();
        float height = graphRect.height();
        
        float nx = (x - graphRect.left) / width;
        float ny = (graphRect.bottom - y) / height;

        // Extended range slightly to allow grabbing points on edge easily
        nx = Math.max(0f, Math.min(1f, nx));
        ny = Math.max(0f, Math.min(1f, ny));

        List<PointF> points = channelPoints.get(activeChannel);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePointIndex = getNearestPointIndex(points, x, y);
                
                // Add new point logic
                if (activePointIndex == -1) {
                    if (isTouchNearCurve(nx, ny)) {
                        PointF newPoint = new PointF(nx, ny);
                        points.add(newPoint);
                        sortPoints(points);
                        activePointIndex = points.indexOf(newPoint);
                        updateCurvePaths();
                        invalidate();
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else {
                    invalidate(); 
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    
                    // --- Movement Constraints ---
                    if (activePointIndex == 0) {
                        p.x = 0;
                        p.y = ny; 
                    } else if (activePointIndex == points.size() - 1) {
                        p.x = 1;
                        p.y = ny;
                    } 
                    else {
                        PointF prev = points.get(activePointIndex - 1);
                        PointF next = points.get(activePointIndex + 1);
                        
                        // Buffer prevents points crossing
                        float buffer = 0.02f; 
                        float minX = prev.x + buffer;
                        float maxX = next.x - buffer;
                        
                        // Safe clamping
                        if (minX >= maxX) {
                             p.x = (prev.x + next.x) / 2;
                        } else {
                             p.x = Math.max(minX, Math.min(maxX, nx));
                        }
                        
                        p.y = ny;
                    }
                    
                    updateCurvePaths();
                    invalidate();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointIndex = -1;
                if (listener != null) listener.onChange();
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    private void handleDoubleTap(float x, float y) {
        List<PointF> points = channelPoints.get(activeChannel);
        int index = getNearestPointIndex(points, x, y);
        
        if (index != -1) {
            // Delete midpoint
            if (index > 0 && index < points.size() - 1) {
                points.remove(index);
            } 
            // Reset endpoints (Black/White points)
            else if (index == 0) {
                points.get(0).y = 0f;
            } else if (index == points.size() - 1) {
                points.get(index).y = 1f;
            }
            
            activePointIndex = -1;
            updateCurvePaths();
            if (listener != null) listener.onChange();
            invalidate();
        }
    }

    private int getNearestPointIndex(List<PointF> points, float touchX, float touchY) {
        int bestIndex = -1;
        float minDst = Float.MAX_VALUE;
        // Increase touch target for usability
        float threshold = touchThresholdPx * 1.5f; 

        for (int i = 0; i < points.size(); i++) {
            PointF p = points.get(i);
            float px = graphRect.left + (p.x * graphRect.width());
            float py = graphRect.bottom - (p.y * graphRect.height());

            float dx = px - touchX;
            float dy = py - touchY;
            float dist = (float) Math.hypot(dx, dy);

            if (dist < threshold && dist < minDst) {
                minDst = dist;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private boolean isTouchNearCurve(float nx, float ny) {
        int index = (int) (nx * 255);
        if (index < 0) index = 0;
        if (index > 255) index = 255;
        
        float curveY = cachedCurve[index];
        float diff = Math.abs(curveY - ny);
        
        return diff < 0.1f; // 10% height allowance
    }

    private void sortPoints(List<PointF> points) {
        Collections.sort(points, (p1, p2) -> Float.compare(p1.x, p2.x));
    }
}
