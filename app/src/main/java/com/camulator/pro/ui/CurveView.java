package com.camulator.pro.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CurveView extends View {

    private Paint gridPaint;
    private Paint curvePaint;
    private Paint pointPaint;
    private List<PointF> points = new ArrayList<>();
    private OnCurveChangeListener listener;
    private int activePointIndex = -1;

    public interface OnCurveChangeListener {
        void onChange();
    }

    public CurveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint();
        gridPaint.setColor(Color.DKGRAY);
        gridPaint.setStrokeWidth(2f);

        curvePaint = new Paint();
        curvePaint.setColor(Color.WHITE);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(4f);
        curvePaint.setAntiAlias(true);

        pointPaint = new Paint();
        pointPaint.setColor(Color.YELLOW);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        // Initial points (Linear)
        points.add(new PointF(0, 1)); // Bottom-Left (Visual) -> 0,1 in normalized logic (1 is bottom in Android Y)
        points.add(new PointF(1, 0)); // Top-Right -> 1,0
    }

    public void setOnCurveChangeListener(OnCurveChangeListener listener) {
        this.listener = listener;
    }

    public List<PointF> getControlPoints() {
        return points;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        // Draw 4x4 Grid
        for (int i = 1; i < 4; i++) {
            float x = i * (w / 4);
            canvas.drawLine(x, 0, x, h, gridPaint);
            float y = i * (h / 4);
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        // Draw Curve (Simple Cubic Hermite Spline or Catmull-Rom simulation for visualization)
        if (points.size() >= 2) {
            Path path = new Path();
            // Sort points by X
            Collections.sort(points, (p1, p2) -> Float.compare(p1.x, p2.x));
            
            // Map normalized points to view coordinates
            PointF start = points.get(0);
            path.moveTo(start.x * w, start.y * h);

            for (int i = 0; i < points.size() - 1; i++) {
                PointF p1 = points.get(i);
                PointF p2 = points.get(i+1);
                
                // Simple linear for prototype, in real Monotone Spline needed
                // For visualization here we use LineTo, but in ImageProcessor we use Spline
                path.lineTo(p2.x * w, p2.y * h);
            }
            canvas.drawPath(path, curvePaint);
        }

        // Draw Points
        for (PointF p : points) {
            canvas.drawCircle(p.x * w, p.y * h, 15, pointPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float w = getWidth();
        float h = getHeight();
        float normalizedX = Math.max(0, Math.min(1, x / w));
        float normalizedY = Math.max(0, Math.min(1, y / h));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePointIndex = getNearestPointIndex(normalizedX, normalizedY);
                if (activePointIndex == -1) {
                    // Add new point
                    points.add(new PointF(normalizedX, normalizedY));
                    activePointIndex = points.size() - 1;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    // Don't move endpoints X
                    if (activePointIndex > 0 && activePointIndex < points.size() -1) {
                        p.x = normalizedX;
                    }
                    p.y = normalizedY;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                activePointIndex = -1;
                if (listener != null) listener.onChange();
                break;
        }
        return true;
    }

    private int getNearestPointIndex(float nx, float ny) {
        float threshold = 0.1f;
        for (int i = 0; i < points.size(); i++) {
            PointF p = points.get(i);
            if (Math.abs(p.x - nx) < threshold && Math.abs(p.y - ny) < threshold) {
                return i;
            }
        }
        return -1;
    }
}