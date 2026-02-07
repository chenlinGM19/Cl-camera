package com.camulator.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.camulator.pro.databinding.ActivityMainBinding;
import com.camulator.pro.processor.ImageProcessor;
import com.camulator.pro.ui.CurveView;
import com.camulator.pro.ui.ColorWheelView;
import com.camulator.pro.utils.WatermarkUtil;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.slider.Slider;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private FusedLocationProviderClient fusedLocationClient;
    
    // Aspect Ratio
    private static final int AR_4_3 = 0;
    private static final int AR_1_1 = 1;
    private static final int AR_16_9 = 2;
    private int currentAspectRatioMode = AR_4_3;

    private float currentZoomRatio = 1f;
    private float minZoomRatio = 1f;
    private float maxZoomRatio = 10f;
    private int currentAlign = 0;
    
    // Edit Params - Volatile for thread safety
    private volatile ImageProcessor.EditParams editParams = new ImageProcessor.EditParams();
    private volatile Map<CurveView.Channel, List<PointF>> previewCurves;
    
    private int activeColorGradeMode = 0; // 0=Shadow, 1=Mid, 2=High
    
    private long lastHistogramUpdate = 0;
    private static final long HISTOGRAM_UPDATE_INTERVAL_MS = 66;
    private boolean isMenuOpen = false;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String PREFS_NAME = "CamulatorPrefs";
    
    private final List<TextView> focalViews = new ArrayList<>();
    
    // Manual Exposure State
    private boolean isManualExposure = false;
    private Range<Long> exposureTimeRange;
    private static final int MANUAL_ISO = 640; // Fixed ISO for Manual S-mode
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        hideSystemUI();
        setupUI(); // Setup UI before loading settings to ensure views are ready
        loadSettings();
        
        // Init previewCurves with default state
        previewCurves = binding.curveView.getControlPointsCopy();

        updatePreviewLayout();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupEditorUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        
        editor.putBoolean("footer_mode", binding.rbFooter.isChecked());
        editor.putBoolean("white_bg", binding.toggleBgColor.isChecked());
        editor.putInt("align", currentAlign);
        editor.putString("custom_text", binding.etWatermarkText.getText().toString());
        editor.putInt("height_progress", binding.seekHeight.getProgress());
        editor.putInt("aspect_ratio_mode", currentAspectRatioMode);
        
        // Watermark flags
        editor.putBoolean("wm_logo", binding.swLogo.isChecked());
        editor.putBoolean("wm_date", binding.swDate.isChecked());
        editor.putBoolean("wm_gps", binding.swGPS.isChecked());
        editor.putBoolean("wm_city", binding.cbCity.isChecked());
        editor.putBoolean("wm_street", binding.cbStreet.isChecked());
        
        // Save simple edit params
        editor.putInt("p_high", editParams.highlights);
        editor.putInt("p_shad", editParams.shadows);
        editor.putInt("p_wht", editParams.whites);
        editor.putInt("p_blk", editParams.blacks);
        
        editor.apply();
    }
    
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        boolean footerMode = prefs.getBoolean("footer_mode", true);
        if (footerMode) binding.rbFooter.setChecked(true); else binding.rbOverlay.setChecked(true);
        binding.toggleBgColor.setChecked(prefs.getBoolean("white_bg", true));
        updateAlignUI(prefs.getInt("align", 0));
        binding.etWatermarkText.setText(prefs.getString("custom_text", "Camulator Pro"));
        
        int heightProg = prefs.getInt("height_progress", 40); // Default 4%
        binding.seekHeight.setProgress(heightProg); 
        binding.tvWatermarkSizeLabel.setText(String.format(Locale.US, "Size: %.1f%%", heightProg / 10f));

        currentAspectRatioMode = prefs.getInt("aspect_ratio_mode", AR_4_3);
        
        // Watermark Flags
        binding.swLogo.setChecked(prefs.getBoolean("wm_logo", true));
        binding.swDate.setChecked(prefs.getBoolean("wm_date", true));
        binding.swGPS.setChecked(prefs.getBoolean("wm_gps", true));
        binding.cbCity.setChecked(prefs.getBoolean("wm_city", true));
        binding.cbStreet.setChecked(prefs.getBoolean("wm_street", false));

        // Load params
        editParams.highlights = prefs.getInt("p_high", 0);
        editParams.shadows = prefs.getInt("p_shad", 0);
        editParams.whites = prefs.getInt("p_wht", 0);
        editParams.blacks = prefs.getInt("p_blk", 0);
        
        // UI Updates for sliders
        binding.seekHighlights.setProgress(editParams.highlights + 100);
        binding.seekShadows.setProgress(editParams.shadows + 100);
        binding.seekWhites.setProgress(editParams.whites + 100);
        binding.seekBlacks.setProgress(editParams.blacks + 100);
    }

    private void setupUI() {
        binding.btnShutter.setOnClickListener(v -> takePhoto());
        binding.btnGallery.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, GalleryActivity.class)));
        
        focalViews.add(binding.focal16mm);
        focalViews.add(binding.focal24mm);
        focalViews.add(binding.focal35mm);
        focalViews.add(binding.focal50mm);
        focalViews.add(binding.focal85mm);

        setupFocalLength(binding.focal16mm, 0.5f);
        setupFocalLength(binding.focal24mm, 1.0f);
        setupFocalLength(binding.focal35mm, 1.5f);
        setupFocalLength(binding.focal50mm, 2.0f);
        setupFocalLength(binding.focal85mm, 3.5f);
        updateFocalLengthSelection(binding.focal24mm);

        binding.btnAspectRatio.setOnClickListener(v -> toggleAspectRatio());
        binding.btnEdit.setOnClickListener(v -> toggleEditPanel());
        
        // Exposure Mode Toggle
        binding.btnExposureMode.setOnClickListener(v -> toggleExposureMode());
        
        // Slider Control
        binding.evSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (camera == null) return;
            
            if (isManualExposure) {
                // Manual Shutter Speed Logic
                if (exposureTimeRange != null) {
                    double pct = value / 100.0;
                    // Logarithmic mapping for natural time scale
                    // Start from 1/10000s (100us) to max (usually 1s or 30s)
                    long min = exposureTimeRange.getLower(); 
                    long max = exposureTimeRange.getUpper();
                    
                    // Clamp min to usable fast shutter if hardware allows faster than needed
                    if (min < 100000L) min = 100000L; // 1/10000s
                    // Clamp max to 1 second for usability if device goes to 30s
                    long practicalMax = 1000000000L; // 1s
                    if (max > practicalMax) max = practicalMax;
                    
                    // Log formula: time = min * (max/min)^pct
                    double timeNs = min * Math.pow((double)max / min, pct);
                    long finalTime = (long) timeNs;
                    
                    updateManualExposure(finalTime);
                    updateShutterLabel(finalTime);
                }
            } else {
                // Auto EV Logic
                CameraControl control = camera.getCameraControl();
                // Map 0-100 slider to -10 to +10 range logic if we changed slider range?
                // Actually EV slider range is usually small steps.
                // Let's remap slider value based on mode switch.
                int index = (int) value; // EV slider is -10 to +10
                ExposureState state = camera.getCameraInfo().getExposureState();
                Range<Integer> range = state.getExposureCompensationRange();
                if (range.contains(index)) {
                     control.setExposureCompensationIndex(index);
                }
            }
        });
    }
    
    private void toggleExposureMode() {
        if (camera == null) return;
        isManualExposure = !isManualExposure;
        
        if (isManualExposure) {
            // Switch to Manual (Shutter Priority simulation)
            binding.btnExposureMode.setText("S");
            binding.btnExposureMode.setBackgroundColor(Color.parseColor("#FF9800"));
            
            // Reconfigure Slider for Shutter (0 to 100%)
            binding.evSlider.setValueFrom(0f);
            binding.evSlider.setValueTo(100f);
            binding.evSlider.setValue(50f); // Default middle
            binding.evSlider.setStepSize(1f);
            
            // Initial Manual Set
            // We set a safe ISO (640) and middle shutter speed initially
            updateManualExposure(-1); // -1 triggers calculation from current slider value
            
        } else {
            // Switch to Auto
            binding.btnExposureMode.setText("Auto");
            binding.btnExposureMode.setBackgroundColor(Color.WHITE);
            
            // Reset Camera to Auto
            Camera2CameraControl c2 = Camera2CameraControl.from(camera.getCameraControl());
            c2.setCaptureRequestOptions(new CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .build());
                
            // Reconfigure Slider for EV (-10 to +10 approx)
            ExposureState state = camera.getCameraInfo().getExposureState();
            Range<Integer> range = state.getExposureCompensationRange();
            
            // Clamp visual range to -10/+10 or hardware max
            float min = Math.max(-10, range.getLower());
            float max = Math.min(10, range.getUpper());
            
            binding.evSlider.setValueFrom(min);
            binding.evSlider.setValueTo(max);
            binding.evSlider.setValue(0f);
            binding.evSlider.setStepSize(1f);
            
            binding.tvExposureMin.setText("" + (int)min);
            binding.tvExposureMax.setText("+" + (int)max);
        }
    }
    
    private void updateManualExposure(long specificTimeNs) {
        if (camera == null) return;
        
        long timeNs = specificTimeNs;
        if (timeNs == -1 && exposureTimeRange != null) {
            // Recalculate from slider
            float val = binding.evSlider.getValue();
            long min = Math.max(exposureTimeRange.getLower(), 100000L); 
            long max = Math.min(exposureTimeRange.getUpper(), 1000000000L);
            timeNs = (long) (min * Math.pow((double)max / min, val / 100.0));
            updateShutterLabel(timeNs);
        }
        
        if (timeNs <= 0) return;

        Camera2CameraControl c2 = Camera2CameraControl.from(camera.getCameraControl());
        CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
        
        // Manual Mode requires AE_MODE_OFF or OFF_KEEP_STATE
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, timeNs);
        // We must set Sensitivity (ISO) when AE is OFF.
        // For "S" mode simulation, we fix ISO or use a "Safe" value.
        builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, MANUAL_ISO); 
        
        c2.setCaptureRequestOptions(builder.build());
    }
    
    private void updateShutterLabel(long ns) {
        String label;
        if (ns >= 1000000000L) {
            double sec = ns / 1.0e9;
            label = String.format(Locale.US, "%.1f\"", sec);
        } else {
            long fraction = 1000000000L / ns;
            label = "1/" + fraction;
        }
        binding.tvExposureMin.setText(label);
        // Clear Max label or use it for info?
        // Let's keep Max as "+" just to show direction or clear it.
        binding.tvExposureMax.setText(""); 
    }
    
    private void setupEditorUI() {
        // Main Tabs
        View.OnClickListener tabListener = v -> {
            binding.containerCurves.setVisibility(View.GONE);
            binding.containerLight.setVisibility(View.GONE);
            binding.containerColor.setVisibility(View.GONE);
            binding.containerWatermark.setVisibility(View.GONE);
            
            ((TextView)binding.tabCurves).setTextColor(Color.WHITE);
            ((TextView)binding.tabLight).setTextColor(Color.WHITE);
            ((TextView)binding.tabColor).setTextColor(Color.WHITE);
            ((TextView)binding.tabWatermark).setTextColor(Color.WHITE);
            
            ((TextView)v).setTextColor(Color.parseColor("#FF9800"));
            
            if (v == binding.tabCurves) binding.containerCurves.setVisibility(View.VISIBLE);
            else if (v == binding.tabLight) binding.containerLight.setVisibility(View.VISIBLE);
            else if (v == binding.tabColor) binding.containerColor.setVisibility(View.VISIBLE);
            else if (v == binding.tabWatermark) binding.containerWatermark.setVisibility(View.VISIBLE);
        };
        
        binding.tabCurves.setOnClickListener(tabListener);
        binding.tabLight.setOnClickListener(tabListener);
        binding.tabColor.setOnClickListener(tabListener);
        binding.tabWatermark.setOnClickListener(tabListener);

        // Curve Channels
        View.OnClickListener channelListener = v -> {
            binding.channelRGB.setTextColor(Color.WHITE);
            binding.channelR.setTextColor(Color.parseColor("#FF4444"));
            binding.channelG.setTextColor(Color.parseColor("#44FF44"));
            binding.channelB.setTextColor(Color.parseColor("#4444FF"));
            
            ((TextView)v).setTextColor(Color.parseColor("#FF9800"));
            
            if (v == binding.channelRGB) binding.curveView.setActiveChannel(CurveView.Channel.RGB);
            else if (v == binding.channelR) binding.curveView.setActiveChannel(CurveView.Channel.RED);
            else if (v == binding.channelG) binding.curveView.setActiveChannel(CurveView.Channel.GREEN);
            else if (v == binding.channelB) binding.curveView.setActiveChannel(CurveView.Channel.BLUE);
        };
        
        binding.channelRGB.setOnClickListener(channelListener);
        binding.channelR.setOnClickListener(channelListener);
        binding.channelG.setOnClickListener(channelListener);
        binding.channelB.setOnClickListener(channelListener);
        
        // Curve change listener - UPDATE PREVIEW CURVES
        binding.curveView.setOnCurveChangeListener(() -> {
            previewCurves = binding.curveView.getControlPointsCopy();
        });
        
        // Light Sliders - UPDATE PREVIEW PARAMS
        SeekBar.OnSeekBarChangeListener lightListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress - 100; // Map 0-200 to -100 to 100
                if (seekBar == binding.seekHighlights) editParams.highlights = val;
                else if (seekBar == binding.seekShadows) editParams.shadows = val;
                else if (seekBar == binding.seekWhites) editParams.whites = val;
                else if (seekBar == binding.seekBlacks) editParams.blacks = val;
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        binding.seekHighlights.setOnSeekBarChangeListener(lightListener);
        binding.seekShadows.setOnSeekBarChangeListener(lightListener);
        binding.seekWhites.setOnSeekBarChangeListener(lightListener);
        binding.seekBlacks.setOnSeekBarChangeListener(lightListener);
        
        // Color Grade Modes - UPDATE PREVIEW PARAMS
        View.OnClickListener gradeListener = v -> {
            binding.btnGradeShadows.setTextColor(Color.WHITE);
            binding.btnGradeMids.setTextColor(Color.WHITE);
            binding.btnGradeHighs.setTextColor(Color.WHITE);
            ((TextView)v).setTextColor(Color.parseColor("#FF9800"));
            
            if (v == binding.btnGradeShadows) activeColorGradeMode = 0;
            else if (v == binding.btnGradeMids) activeColorGradeMode = 1;
            else if (v == binding.btnGradeHighs) activeColorGradeMode = 2;
            
            // Restore wheel state
            binding.colorWheel.reset(); 
            binding.colorInfo.setText("Select Color");
        };
        binding.btnGradeShadows.setOnClickListener(gradeListener);
        binding.btnGradeMids.setOnClickListener(gradeListener);
        binding.btnGradeHighs.setOnClickListener(gradeListener);
        
        binding.colorWheel.setOnColorChangeListener((hue, sat) -> {
            String txt = String.format(Locale.US, "H:%.0f S:%.2f", hue, sat);
            binding.colorInfo.setText(txt);
            if (activeColorGradeMode == 0) { editParams.shadowHue = hue; editParams.shadowSat = sat; }
            else if (activeColorGradeMode == 1) { editParams.midHue = hue; editParams.midSat = sat; }
            else { editParams.highlightHue = hue; editParams.highlightSat = sat; }
        });
        
        // Align Buttons
        binding.alignLeft.setOnClickListener(v -> updateAlignUI(0));
        binding.alignCenter.setOnClickListener(v -> updateAlignUI(1));
        binding.alignRight.setOnClickListener(v -> updateAlignUI(2));
        
        // Height Slider with Real-time Feedback
        binding.seekHeight.setMax(100); // 0.0% to 10.0%
        binding.seekHeight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float percent = progress / 10f;
                binding.tvWatermarkSizeLabel.setText(String.format(Locale.US, "Size: %.1f%%", percent));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateAlignUI(int align) {
        currentAlign = align;
        binding.alignLeft.setColorFilter(align == 0 ? Color.WHITE : Color.GRAY);
        binding.alignCenter.setColorFilter(align == 1 ? Color.WHITE : Color.GRAY);
        binding.alignRight.setColorFilter(align == 2 ? Color.WHITE : Color.GRAY);
    }

    private void setupFocalLength(TextView view, float zoom) {
        view.setOnClickListener(v -> {
            if (camera != null) {
                float z = Math.max(minZoomRatio, Math.min(zoom, maxZoomRatio));
                camera.getCameraControl().setZoomRatio(z);
                currentZoomRatio = z;
                updateFocalLengthSelection(view);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            }
        });
    }
    
    private void updateFocalLengthSelection(TextView active) {
        for (TextView tv : focalViews) {
            if (tv == active) {
                tv.setTextColor(Color.parseColor("#FF9800"));
                tv.setTypeface(null, Typeface.BOLD);
            } else {
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(null, Typeface.NORMAL);
            }
        }
    }
    
    private void updateFocalLengthVisibility(TextView view, float targetZoom) {
        if (targetZoom < minZoomRatio || targetZoom > maxZoomRatio) {
            view.setAlpha(0.3f);
            view.setEnabled(false);
        } else {
            view.setAlpha(1.0f);
            view.setEnabled(true);
        }
    }

    private void toggleAspectRatio() {
        currentAspectRatioMode++;
        if (currentAspectRatioMode > AR_16_9) currentAspectRatioMode = AR_4_3;
        updatePreviewLayout();
        startCamera(); 
    }
    
    private void updatePreviewLayout() {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) binding.viewFinder.getLayoutParams();
        // Sync overlay params
        ConstraintLayout.LayoutParams overlayParams = (ConstraintLayout.LayoutParams) binding.previewOverlay.getLayoutParams();
        
        switch (currentAspectRatioMode) {
            case AR_16_9: params.dimensionRatio = "H,9:16"; break;
            case AR_1_1: params.dimensionRatio = "H,1:1"; break;
            case AR_4_3: default: params.dimensionRatio = "H,3:4"; break;
        }
        overlayParams.dimensionRatio = params.dimensionRatio;
        
        binding.viewFinder.setLayoutParams(params);
        binding.previewOverlay.setLayoutParams(overlayParams);
    }
    
    private void toggleEditPanel() {
        isMenuOpen = !isMenuOpen;
        binding.layoutEditor.setVisibility(isMenuOpen ? View.VISIBLE : View.GONE);
        if(!isMenuOpen) {
            saveSettings();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) { }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        
        int targetAspectRatio = (currentAspectRatioMode == AR_16_9) ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;

        Preview preview = new Preview.Builder().setTargetAspectRatio(targetAspectRatio).build();
        preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(targetAspectRatio)
                .build();
                
        // Real-time processing setup
        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480)) // Low res for performance
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // RGBA for Bitmap manipulation
                .build();
                
        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            // 1. Convert to Bitmap
            Bitmap bmp = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());

            // 2. Rotate if needed
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                // Note: creating new bitmap is heavy, but at 640x480 it's manageable on modern devices
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (bmp != rotated) bmp.recycle();
                bmp = rotated;
            }

            // 3. Apply Real-time Effects
            // We reuse the same bitmap to avoid allocation
            ImageProcessor.applyProcessing(bmp, previewCurves, editParams);
            
            // 4. Update UI
            final Bitmap finalBmp = bmp;
            long currentTime = System.currentTimeMillis();
            
            // Histogram Update Check (Histogram now uses the processed bitmap)
            int[] histogram = null;
            if (binding.layoutEditor.getVisibility() == View.VISIBLE && 
               (currentTime - lastHistogramUpdate > HISTOGRAM_UPDATE_INTERVAL_MS)) {
                
                int[] pixels = new int[finalBmp.getWidth() * finalBmp.getHeight()];
                finalBmp.getPixels(pixels, 0, finalBmp.getWidth(), 0, 0, finalBmp.getWidth(), finalBmp.getHeight());
                histogram = ImageProcessor.calculateLuminanceHistogram(pixels);
                lastHistogramUpdate = currentTime;
            }
            final int[] finalHistogram = histogram;

            runOnUiThread(() -> {
                binding.previewOverlay.setImageBitmap(finalBmp);
                // Ensure raw preview is hidden once we have processed frames
                if (binding.viewFinder.getAlpha() > 0) {
                    binding.viewFinder.animate().alpha(0f).setDuration(200).start();
                }
                
                if (finalHistogram != null) {
                    binding.curveView.setHistogramData(finalHistogram);
                }
            });

            image.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            
            // Get Camera Characteristics for Manual Exposure
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            exposureTimeRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            
            // Setup Zoom
            camera.getCameraInfo().getZoomState().observe(this, state -> {
                minZoomRatio = state.getMinZoomRatio();
                maxZoomRatio = state.getMaxZoomRatio();
                updateFocalLengthVisibility(binding.focal16mm, 0.5f);
                updateFocalLengthVisibility(binding.focal24mm, 1.0f);
                updateFocalLengthVisibility(binding.focal35mm, 1.5f);
                updateFocalLengthVisibility(binding.focal50mm, 2.0f);
                updateFocalLengthVisibility(binding.focal85mm, 3.5f);
            });
            
        } catch (Exception exc) {
            Toast.makeText(this, "Camera init failed", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void takePhoto() {
        if (imageCapture == null) return;

        binding.shutterOverlay.setVisibility(View.VISIBLE);
        binding.shutterOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            binding.shutterOverlay.setVisibility(View.GONE);
            binding.shutterOverlay.setAlpha(1f);
        });
        binding.btnShutter.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        
        binding.processingProgress.setVisibility(View.VISIBLE);
        binding.btnShutter.setEnabled(false);

        File tempFile = new File(getCacheDir(), "temp_capture.jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(tempFile).build();

        final WatermarkUtil.WatermarkConfig wmConfig = new WatermarkUtil.WatermarkConfig();
        wmConfig.isFooterMode = binding.rbFooter.isChecked();
        wmConfig.isWhiteBg = binding.toggleBgColor.isChecked();
        wmConfig.align = currentAlign;
        wmConfig.customText = binding.etWatermarkText.getText().toString();
        
        // New Flag Configs
        wmConfig.showLogo = binding.swLogo.isChecked();
        wmConfig.showDate = binding.swDate.isChecked();
        wmConfig.showGPS = binding.swGPS.isChecked();
        wmConfig.showCity = binding.cbCity.isChecked();
        wmConfig.showStreet = binding.cbStreet.isChecked();
        
        float progress = binding.seekHeight.getProgress(); 
        wmConfig.heightPercent = progress / 1000f; // 0 to 10%
        wmConfig.shouldCrop1to1 = (currentAspectRatioMode == AR_1_1);

        final Map<CurveView.Channel, List<PointF>> curveData = binding.curveView.getControlPointsCopy();
        // Capture current edit params
        final ImageProcessor.EditParams currentParams = new ImageProcessor.EditParams();
        currentParams.highlights = editParams.highlights;
        currentParams.shadows = editParams.shadows;
        currentParams.whites = editParams.whites;
        currentParams.blacks = editParams.blacks;
        currentParams.shadowHue = editParams.shadowHue; currentParams.shadowSat = editParams.shadowSat;
        currentParams.midHue = editParams.midHue; currentParams.midSat = editParams.midSat;
        currentParams.highlightHue = editParams.highlightHue; currentParams.highlightSat = editParams.highlightSat;

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
             imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    cameraExecutor.execute(() -> processAndSaveImage(tempFile, wmConfig, location, curveData, currentParams));
                }
                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    onCaptureFinished(false);
                }
            });
        }).addOnFailureListener(e -> {
             imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    cameraExecutor.execute(() -> processAndSaveImage(tempFile, wmConfig, null, curveData, currentParams));
                }
                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    onCaptureFinished(false);
                }
            });
        });
    }
    
    private void processAndSaveImage(File tempFile, WatermarkUtil.WatermarkConfig config, 
                                     Location location, Map<CurveView.Channel, List<PointF>> curveData,
                                     ImageProcessor.EditParams params) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = true; 
        Bitmap bitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), opts);
        
        if (bitmap == null) {
            onCaptureFinished(false);
            return;
        }
        
        // Rotate & Crop logic
        try {
            ExifInterface exif = new ExifInterface(tempFile.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotationDegrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationDegrees = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationDegrees = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationDegrees = 270;
            
            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) { bitmap.recycle(); bitmap = rotated; }
            }
            
            if (config.shouldCrop1to1) {
                int s = Math.min(bitmap.getWidth(), bitmap.getHeight());
                Bitmap cropped = Bitmap.createBitmap(bitmap, (bitmap.getWidth()-s)/2, (bitmap.getHeight()-s)/2, s, s);
                if (cropped != bitmap) { bitmap.recycle(); bitmap = cropped; }
            }

            // Exif Metadata Construction
            config.exifInfo = "ISO" + (exif.getAttribute(ExifInterface.TAG_ISO) != null ? exif.getAttribute(ExifInterface.TAG_ISO) : "-");
            String f = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if(f!=null) {
                 try {
                     double fl = Double.parseDouble(f);
                     config.exifInfo += "  " + new DecimalFormat("#").format(fl) + "mm";
                 } catch(Exception e){}
            }
            
            // If Manual Shutter, append info if available
            if (isManualExposure) {
                // Actually, the ImageCapture might not reflect manual setting immediately in EXIF
                // But generally hardware handles this.
            }
            
        } catch (IOException e) { }

        // Apply Image Processing
        bitmap = ImageProcessor.applyProcessing(bitmap, curveData, params);

        config.dateStr = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date());
        
        // Location Geocoding
        if (location != null) {
            double lat = location.getLatitude();
            double lon = location.getLongitude();
            String latStr = String.format(Locale.US, "%.4f %s", Math.abs(lat), lat >= 0 ? "N" : "S");
            String lonStr = String.format(Locale.US, "%.4f %s", Math.abs(lon), lon >= 0 ? "E" : "W");
            config.gpsStr = latStr + " " + lonStr;

            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    // Use subAdminArea for District/City-like, Locality for City
                    String city = addr.getSubAdminArea();
                    if (city == null) city = addr.getLocality();
                    
                    String street = addr.getThoroughfare(); 
                    if (street == null) street = addr.getFeatureName(); // Fallback
                    
                    config.locStr = (city != null ? city : "") + "|" + (street != null ? street : "");
                }
            } catch (Exception e) {}
        } else {
            config.gpsStr = "";
            config.locStr = "";
        }
        
        Bitmap finalBitmap = WatermarkUtil.addWatermark(bitmap, config);
        if (bitmap != finalBitmap && !bitmap.isRecycled()) bitmap.recycle();
        
        String fileName = "Cam_" + System.currentTimeMillis() + ".jpg";
        saveToGallery(finalBitmap, fileName);
        finalBitmap.recycle();
        if (tempFile.exists()) tempFile.delete();

        runOnUiThread(() -> onCaptureFinished(true));
    }
    
    private void saveToGallery(Bitmap bitmap, String fileName) {
        try {
            OutputStream fos = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CamulatorPro");
                Uri imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                if (imageUri != null) fos = getContentResolver().openOutputStream(imageUri);
            } else {
                File imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File appDir = new File(imagesDir, "CamulatorPro");
                if (!appDir.exists()) appDir.mkdirs();
                File image = new File(appDir, fileName);
                fos = new FileOutputStream(image);
                MediaScannerConnection.scanFile(this, new String[]{image.getAbsolutePath()}, null, null);
            }
            if (fos != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
                Bitmap thumb = Bitmap.createScaledBitmap(bitmap, 200, 200, false);
                runOnUiThread(() -> binding.thumbnail.setImageBitmap(thumb));
            }
        } catch (Exception e) {}
    }
    
    private void onCaptureFinished(boolean success) {
        runOnUiThread(() -> {
            binding.processingProgress.setVisibility(View.GONE);
            binding.btnShutter.setEnabled(true);
        });
    }

    private boolean allPermissionsGranted() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startCamera();
    }
}