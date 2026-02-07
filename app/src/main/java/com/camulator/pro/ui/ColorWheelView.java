package com.camulator.pro.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class ColorWheelView extends View {

    private Paint wheelPaint;
    private Paint thumbPaint;
    private Paint borderPaint;
    
    private float centerX, centerY, radius;
    private float currentHue = 0f; // 0-360
    private float currentSat = 0f; // 0-1
    
    private OnColorChangeListener listener;

    public interface OnColorChangeListener {
        void onColorChanged(float hue, float sat);
    }

    public ColorWheelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wheelPaint.setStyle(Paint.Style.FILL);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.STROKE);
        thumbPaint.setColor(Color.WHITE);
        thumbPaint.setStrokeWidth(6f);
        
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFF444444);
        borderPaint.setStrokeWidth(2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(centerX, centerY) - 10f;

        // Create Color Wheel Shader
        // 1. Sweep Gradient for Hue
        int[] colors = new int[]{Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED};
        Shader sweep = new SweepGradient(centerX, centerY, colors, null);
        
        // 2. Radial Gradient for Saturation (White center to Transparent edge -> combined means White center to Full Color)
        // Actually, typically Color Wheels are White/Grey in center (Saturation 0).
        // Let's use a Radial Gradient from White (Center) to Transparent (Edge) over the Sweep.
        Shader radial = new RadialGradient(centerX, centerY, radius, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        
        // Compose: DST_OUT isn't quite right for saturation. 
        // Standard approach: Draw Sweep, then draw Radial White->Transparent on top? 
        // Let's just use Sweep for Hue. Visualizing Saturation is harder in one pass without complex composition.
        // Simplified: Just draw the Hue sweep. The distance from center is saturation.
        wheelPaint.setShader(sweep);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Draw Wheel
        canvas.drawCircle(centerX, centerY, radius, wheelPaint);
        
        // Draw Saturation Overlay (White at center fading out)
        // We redraw a circle with radial gradient to simulate saturation
        Paint satPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        satPaint.setShader(new RadialGradient(centerX, centerY, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, radius, satPaint);
        
        canvas.drawCircle(centerX, centerY, radius, borderPaint);

        // Draw Thumb
        float thumbDist = currentSat * radius;
        double angleRad = Math.toRadians(currentHue);
        float thumbX = centerX + (float) (thumbDist * Math.cos(angleRad));
        float thumbY = centerY + (float) (thumbDist * Math.sin(angleRad));

        canvas.drawCircle(thumbX, thumbY, 12f, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() - centerX;
        float y = event.getY() - centerY;
        
        float dist = (float) Math.sqrt(x*x + y*y);
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // Calculate Hue
                double angle = Math.toDegrees(Math.atan2(y, x));
                if (angle < 0) angle += 360;
                currentHue = (float) angle;
                
                // Calculate Saturation
                currentSat = Math.min(1f, dist / radius);
                
                if (listener != null) {
                    listener.onColorChanged(currentHue, currentSat);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
    
    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }
    
    public void reset() {
        currentHue = 0;
        currentSat = 0;
        invalidate();
    }
    
    public float getHue() { return currentHue; }
    public float getSat() { return currentSat; }
}