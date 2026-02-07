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
    private Paint gridPaint, subGridPaint, guidePaint;
    private Paint curvePaint, fillPaint;
    private Paint pointPaint, pointStrokePaint;
    private Paint textPaint, textBgPaint, crosshairPaint, histogramPaint;

    // State
    private Channel activeChannel = Channel.RGB;
    private Map<Channel, List<PointF>> channelPoints = new HashMap<>();
    private OnCurveChangeListener listener;
    private int[] histogramData = new int[256];
    private Path histogramPath = new Path();
    
    // Interaction
    private int activePointIndex = -1;
    private GestureDetector gestureDetector;
    private RectF graphRect = new RectF();
    private float paddingPx, touchThresholdPx;
    
    // Drawing Paths
    private Path curvePath = new Path();
    private Path fillPath = new Path();

    public interface OnCurveChangeListener {
        void onChange();
    }

    public CurveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        paddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
        touchThresholdPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#505050"));
        gridPaint.setStrokeWidth(2f);

        curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(6f); // Thicker for better visibility
        curvePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);

        pointStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointStrokePaint.setColor(Color.BLACK);
        pointStrokePaint.setStyle(Paint.Style.STROKE);
        pointStrokePaint.setStrokeWidth(3f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        
        textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textBgPaint.setColor(0xCC000000);
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setPathEffect(new CornerPathEffect(8));
        
        crosshairPaint = new Paint();
        crosshairPaint.setColor(0x88FFFFFF);
        crosshairPaint.setStrokeWidth(2f);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{15, 15}, 0));
        
        histogramPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        histogramPaint.setStyle(Paint.Style.FILL);
        histogramPaint.setColor(0x50AAAAAA);
        histogramPaint.setPathEffect(new CornerPathEffect(4)); 

        resetChannel(Channel.RGB);
        resetChannel(Channel.RED);
        resetChannel(Channel.GREEN);
        resetChannel(Channel.BLUE);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                handleDoubleTap(e.getX(), e.getY());
                return true;
            }
            @Override
            public boolean onDown(MotionEvent e) { 
                // Don't consume here to allow manual touch handling in onTouchEvent
                return true; 
            }
        });
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
        invalidate();
    }

    public void setOnCurveChangeListener(OnCurveChangeListener listener) {
        this.listener = listener;
    }

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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        graphRect.set(paddingPx, paddingPx, w - paddingPx, h - paddingPx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        float w = graphRect.width();
        float h = graphRect.height();
        float left = graphRect.left;
        float bottom = graphRect.bottom;
        
        drawHistogram(canvas, left, graphRect.top, graphRect.right, bottom, w, h);

        // Grid
        for (int i = 1; i < 4; i++) {
            float x = left + (i * w / 4f);
            canvas.drawLine(x, graphRect.top, x, bottom, gridPaint);
            float y = graphRect.top + (i * h / 4f);
            canvas.drawLine(left, y, graphRect.right, y, gridPaint);
        }
        canvas.drawRect(graphRect, gridPaint);

        // Colors
        int mainColor = 0xFFFFFFFF;
        int dimColor = 0x20FFFFFF;
        switch (activeChannel) {
            case RED: mainColor = 0xFFFF5252; dimColor = 0x30FF5252; break;
            case GREEN: mainColor = 0xFF69F0AE; dimColor = 0x3069F0AE; break;
            case BLUE: mainColor = 0xFF448AFF; dimColor = 0x30448AFF; break;
        }

        curvePaint.setColor(mainColor);
        fillPaint.setShader(new LinearGradient(0, graphRect.top, 0, bottom, dimColor, 0x00000000, Shader.TileMode.CLAMP));

        // Draw Spline
        drawSpline(canvas, w, h, left, bottom);

        // Draw Points
        List<PointF> points = channelPoints.get(activeChannel);
        if (points != null) {
            for (int i = 0; i < points.size(); i++) {
                PointF p = points.get(i);
                float px = left + (p.x * w);
                float py = bottom - (p.y * h);

                boolean isActive = (i == activePointIndex);
                float radius = isActive ? 24f : 16f;

                if (isActive) {
                    canvas.drawLine(px, graphRect.top, px, bottom, crosshairPaint);
                    canvas.drawLine(left, py, graphRect.right, py, crosshairPaint);
                }

                canvas.drawCircle(px, py, radius, pointPaint);
                canvas.drawCircle(px, py, radius, pointStrokePaint);

                if (isActive) {
                    drawTooltip(canvas, p, px, py);
                }
            }
        }
    }
    
    // Calculates and draws the visual Spline path matching ImageProcessor
    private void drawSpline(Canvas canvas, float w, float h, float left, float bottom) {
        List<PointF> points = channelPoints.get(activeChannel);
        if (points == null || points.size() < 2) return;

        curvePath.reset();
        fillPath.reset();
        
        // Use the ImageProcessor's Natural Cubic Spline algo to generate display points
        // 256 steps ensures the visual curve looks identical to the applied curve
        float[] lut = ImageProcessor.calculateCurvePoints(points, 256); 
        
        for (int i = 0; i < lut.length; i++) {
            float x = left + (i / 255f) * w;
            float y = bottom - (lut[i] * h);
            
            if (i == 0) {
                curvePath.moveTo(x, y);
                fillPath.moveTo(x, y);
            } else {
                curvePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }

        fillPath.lineTo(graphRect.right, bottom);
        fillPath.lineTo(left, bottom);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(curvePath, curvePaint);
    }
    
    private void drawHistogram(Canvas canvas, float left, float top, float right, float bottom, float width, float height) {
        if (histogramData == null) return;
        int max = 1;
        for (int val : histogramData) if (val > max) max = val;
        
        histogramPath.reset();
        histogramPath.moveTo(left, bottom);
        
        for (int i = 0; i < 256; i++) {
            float x = left + (i / 255f) * width;
            float hVal = (float) histogramData[i] / max;
            if (hVal > 0) hVal = (float) (Math.log10(1 + 9 * hVal)); 
            float y = bottom - (hVal * height * 0.9f); 
            histogramPath.lineTo(x, y);
        }
        histogramPath.lineTo(right, bottom);
        histogramPath.close();
        canvas.drawPath(histogramPath, histogramPaint);
    }

    private void drawTooltip(Canvas canvas, PointF p, float px, float py) {
        String text = String.format(Locale.US, "%.2f / %.2f", p.x, p.y);
        float textW = textPaint.measureText(text);
        float pad = 16f;
        RectF bg = new RectF(px - textW/2 - pad, py - 100, px + textW/2 + pad, py - 50);
        canvas.drawRect(bg, textBgPaint);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(text, bg.centerX() - textW / 2, bg.centerY() - (fm.descent + fm.ascent) / 2, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Pass to detector first, but do NOT return immediately if it returns true.
        // We need to allow the move/up logic to run unless it was a double tap.
        gestureDetector.onTouchEvent(event);
        
        float x = event.getX();
        float y = event.getY();
        float w = graphRect.width();
        float h = graphRect.height();
        
        float nx = (x - graphRect.left) / w;
        float ny = (graphRect.bottom - y) / h;
        nx = Math.max(0f, Math.min(1f, nx));
        ny = Math.max(0f, Math.min(1f, ny));

        List<PointF> points = channelPoints.get(activeChannel);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePointIndex = getNearestPointIndex(points, x, y);
                if (activePointIndex == -1) {
                    // Check spacing
                    boolean canAdd = true;
                    for(PointF p : points) {
                        if (Math.abs(p.x - nx) < 0.05f) { canAdd = false; break; }
                    }
                    if (canAdd) {
                        PointF newPoint = new PointF(nx, ny);
                        points.add(newPoint);
                        sortPoints(points);
                        activePointIndex = points.indexOf(newPoint);
                        invalidate();
                        if (listener != null) listener.onChange();
                    }
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    // Constraint endpoints to X axis
                    if (activePointIndex == 0) p.x = 0;
                    else if (activePointIndex == points.size() - 1) p.x = 1;
                    else {
                        PointF prev = points.get(activePointIndex - 1);
                        PointF next = points.get(activePointIndex + 1);
                        p.x = Math.max(prev.x + 0.01f, Math.min(next.x - 0.01f, nx));
                    }
                    p.y = ny;
                    invalidate();
                    if (listener != null) listener.onChange();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointIndex = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    private void handleDoubleTap(float x, float y) {
        List<PointF> points = channelPoints.get(activeChannel);
        int index = getNearestPointIndex(points, x, y);
        if (index > 0 && index < points.size() - 1) {
            points.remove(index);
            invalidate();
            if (listener != null) listener.onChange();
        } else if (index == 0 || index == points.size() - 1) {
            // Reset endpoints
            PointF p = points.get(index);
            p.y = (index == 0) ? 0f : 1f;
            invalidate();
            if (listener != null) listener.onChange();
        }
    }

    private int getNearestPointIndex(List<PointF> points, float touchX, float touchY) {
        int bestIndex = -1;
        float minDst = Float.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            PointF p = points.get(i);
            float px = graphRect.left + (p.x * graphRect.width());
            float py = graphRect.bottom - (p.y * graphRect.height());
            float dist = (float) Math.hypot(px - touchX, py - touchY);
            if (dist < touchThresholdPx && dist < minDst) {
                minDst = dist;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private void sortPoints(List<PointF> points) {
        Collections.sort(points, (p1, p2) -> Float.compare(p1.x, p2.x));
    }
}