package com.camulator.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A professional Curve Editing View.
 * Coordinates are normalized 0..1 (X: 0=Black In, 1=White In | Y: 0=Black Out, 1=White Out).
 * NOTE: When drawing, (0,0) is bottom-left visually for a graph.
 */
public class CurveView extends View {

    public enum Channel { RGB, RED, GREEN, BLUE }
    
    public interface OnCurveChangeListener {
        void onCurveChanged();
    }

    private Channel currentChannel = Channel.RGB;
    
    private Paint linePaint, gridPaint, pointPaint, pointSelectedPaint, borderPaint, fillPaint;
    private OnCurveChangeListener listener;
    
    // Normalized 0..1 points. (0,0) = Bottom-Left (Black), (1,1) = Top-Right (White)
    private List<PointF> pointsRGB, pointsR, pointsG, pointsB;
    
    private int activePointIndex = -1;
    private final float TOUCH_RADIUS = 50f; 
    private final float DELETE_THRESHOLD = 0.1f; // Drag 10% outside Y bounds to delete

    public CurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public void setOnCurveChangeListener(OnCurveChangeListener listener) {
        this.listener = listener;
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setShadowLayer(4f, 0f, 1f, Color.BLACK);

        pointSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointSelectedPaint.setColor(Color.YELLOW);
        pointSelectedPaint.setStyle(Paint.Style.FILL);
        pointSelectedPaint.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        pointSelectedPaint.setStrokeWidth(4f);

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#44FFFFFF"));
        gridPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        
        borderPaint = new Paint();
        borderPaint.setColor(Color.parseColor("#88FFFFFF"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAlpha(40);

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
        list.add(new PointF(0f, 0f)); // Bottom-Left (Black)
        list.add(new PointF(1f, 1f)); // Top-Right (White)
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        // 1. Draw Grid (4x4)
        for (int i=1; i<4; i++) {
            float pos = i / 4f;
            canvas.drawLine(w*pos, 0, w*pos, h, gridPaint); // Vert
            canvas.drawLine(0, h*pos, w, h*pos, gridPaint); // Horiz
        }
        // Diagonal ref line
        canvas.drawLine(0, h, w, 0, gridPaint);
        
        canvas.drawRect(0, 0, w, h, borderPaint);

        // 2. Setup Colors
        int color;
        switch (currentChannel) {
            case RED: color = Color.parseColor("#FF453A"); break; // Red
            case GREEN: color = Color.parseColor("#32D74B"); break; // Green
            case BLUE: color = Color.parseColor("#0A84FF"); break; // Blue
            default: color = Color.WHITE; break;
        }
        linePaint.setColor(color);
        fillPaint.setColor(color);
        fillPaint.setAlpha(30);

        List<PointF> points = getCurrentPoints();
        // Ensure points are sorted by X for calculation
        Collections.sort(points, (o1, o2) -> Float.compare(o1.x, o2.x));

        // 3. Draw Spline Path
        if (points.size() >= 2) {
            // Generate LUT for visual path drawing
            // Note: points are 0..1 (math coords). 
            // LUT generation expects math coords (0,0)=black.
            int[] lut = ImageUtils.generateLUT(points);
            
            Path path = new Path();
            Path fillPath = new Path();
            
            // Map LUT (0-255) to View Coords (W, H)
            // X: 0 -> 0, 255 -> w
            // Y: 0 -> h (bottom), 255 -> 0 (top)
            
            float startY = h - (lut[0] / 255f * h);
            path.moveTo(0, startY);
            fillPath.moveTo(0, h); // Anchor bottom-left
            fillPath.lineTo(0, startY);
            
            for (int i = 1; i < 256; i++) {
                float x = (i / 255f) * w;
                float val = lut[i] / 255f;
                float y = h - (val * h); // Invert Y for canvas
                path.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            
            float endY = h - (lut[255] / 255f * h);
            fillPath.lineTo(w, endY);
            fillPath.lineTo(w, h); // Anchor bottom-right
            fillPath.close();
            
            canvas.drawPath(fillPath, fillPaint);
            canvas.drawPath(path, linePaint);
        }

        // 4. Draw Control Points
        for (int i=0; i<points.size(); i++) {
            PointF p = points.get(i);
            // Convert normalized math coords to canvas coords
            float cx = p.x * w;
            float cy = h - (p.y * h); 
            
            float rad = (i == activePointIndex) ? 20f : 14f;
            Paint paint = (i == activePointIndex) ? pointSelectedPaint : pointPaint;
            
            canvas.drawCircle(cx, cy, rad, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float ex = event.getX();
        float ey = event.getY();
        float w = getWidth();
        float h = getHeight();
        
        // Normalize touch to 0..1 Math coords
        // X: 0..1
        // Y: Canvas 0 is Top(1), Canvas H is Bottom(0) -> Math Y = 1 - (ey/h)
        float nx = Math.max(0, Math.min(1, ex / w));
        float nyRaw = 1f - (ey / h); // Unclamped for delete detection
        float ny = Math.max(0, Math.min(1, nyRaw));

        List<PointF> points = getCurrentPoints();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePointIndex = -1;
                float closestDist = Float.MAX_VALUE;
                
                // Find closest point based on screen distance
                for (int i = 0; i < points.size(); i++) {
                    PointF p = points.get(i);
                    float cx = p.x * w;
                    float cy = h - (p.y * h);
                    float dist = (float) Math.hypot(ex - cx, ey - cy);
                    
                    if (dist < TOUCH_RADIUS && dist < closestDist) {
                        activePointIndex = i;
                        closestDist = dist;
                    }
                }
                
                // If no point clicked, add new one (if strictly inside bounds)
                if (activePointIndex == -1) {
                    if (nyRaw >= 0 && nyRaw <= 1) {
                        if (points.size() < 14) { // Max points
                            points.add(new PointF(nx, ny));
                            // Re-sort to find new index
                            Collections.sort(points, (o1, o2) -> Float.compare(o1.x, o2.x));
                            // Find our point again
                            for(int i=0; i<points.size(); i++) {
                                if(points.get(i).x == nx && points.get(i).y == ny) {
                                    activePointIndex = i;
                                    break;
                                }
                            }
                            invalidate();
                            notifyListener();
                        }
                    }
                }
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    
                    // Handle Endpoints (Index 0 and Last) - X locked
                    // But wait, sort order might change index. 
                    // Logic: Points with X=0 or X=1 are typically anchors.
                    // Let's identify anchors by index after sort? No, index shifts.
                    // Professional curves: usually 0,0 and 1,1 are fixed X, but Y can move?
                    // Actually, standard curves allow moving Black Point and White Point Y values, but X stays 0 and 1.
                    
                    boolean isStart = (activePointIndex == 0);
                    boolean isEnd = (activePointIndex == points.size() - 1);
                    
                    if (isStart) {
                        p.x = 0f;
                        p.y = ny; 
                    } else if (isEnd) {
                        p.x = 1f;
                        p.y = ny;
                    } else {
                        // Mid points
                        
                        // Check for delete gesture (drag far up or down)
                        if (nyRaw < -DELETE_THRESHOLD || nyRaw > 1.0f + DELETE_THRESHOLD) {
                            points.remove(activePointIndex);
                            activePointIndex = -1; // Reset
                            invalidate();
                            notifyListener();
                            return true;
                        }
                        
                        // Constrain X between neighbors to ensure monotonicity
                        PointF prev = points.get(activePointIndex - 1);
                        PointF next = points.get(activePointIndex + 1);
                        
                        float minX = prev.x + 0.02f; // Minimum separation
                        float maxX = next.x - 0.02f;
                        
                        p.x = Math.max(minX, Math.min(maxX, nx));
                        p.y = ny;
                    }
                    
                    invalidate();
                    notifyListener();
                }
                break;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointIndex = -1;
                invalidate();
                break;
        }
        return true;
    }
}