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
import java.util.Comparator;
import java.util.List;

public class CurveView extends View {

    private Paint linePaint;
    private Paint pointPaint;
    private Paint gridPaint;
    private List<PointF> points;
    private int activePointIndex = -1;

    public CurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(5f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setAntiAlias(true);

        pointPaint = new Paint();
        pointPaint.setColor(Color.YELLOW);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#44FFFFFF"));
        gridPaint.setStrokeWidth(2f);

        points = new ArrayList<>();
        // Default linear curve (Normalized 0..1)
        points.add(new PointF(0f, 1f)); // Bottom Left (Visual in math is 0,0, but canvas Y is inverted)
        points.add(new PointF(0.25f, 0.75f));
        points.add(new PointF(0.75f, 0.25f));
        points.add(new PointF(1f, 0f)); // Top Right
    }

    public List<PointF> getPoints() {
        return points;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        // Draw Grid
        canvas.drawLine(w/4, 0, w/4, h, gridPaint);
        canvas.drawLine(w/2, 0, w/2, h, gridPaint);
        canvas.drawLine(3*w/4, 0, 3*w/4, h, gridPaint);
        canvas.drawLine(0, h/4, w, h/4, gridPaint);
        canvas.drawLine(0, h/2, w, h/2, gridPaint);
        canvas.drawLine(0, 3*h/4, w, 3*h/4, gridPaint);

        // Draw Spline
        if (points.size() > 1) {
            Path path = new Path();
            // Sort points by X
            Collections.sort(points, new Comparator<PointF>() {
                @Override
                public int compare(PointF o1, PointF o2) {
                    return Float.compare(o1.x, o2.x);
                }
            });

            path.moveTo(points.get(0).x * w, points.get(0).y * h);
            
            // Simple linear connection for this demo (Spline math is verbose for Java snippet)
            for (int i = 1; i < points.size(); i++) {
                path.lineTo(points.get(i).x * w, points.get(i).y * h);
            }
            canvas.drawPath(path, linePaint);
        }

        // Draw Points
        for (PointF p : points) {
            canvas.drawCircle(p.x * w, p.y * h, 15f, pointPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float w = getWidth();
        float h = getHeight();

        // Normalize
        float nx = Math.max(0, Math.min(1, x / w));
        float ny = Math.max(0, Math.min(1, y / h));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Find nearest point
                float minDesc = 0.1f; // Hit radius
                activePointIndex = -1;
                for (int i = 0; i < points.size(); i++) {
                    PointF p = points.get(i);
                    double dist = Math.hypot(p.x - nx, p.y - ny);
                    if (dist < minDesc) {
                        activePointIndex = i;
                        minDesc = (float) dist;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    // Lock start and end X
                    if (activePointIndex == 0) p.x = 0;
                    else if (activePointIndex == points.size()-1) p.x = 1;
                    else p.x = nx;
                    
                    p.y = ny;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                activePointIndex = -1;
                break;
        }
        return true;
    }
}