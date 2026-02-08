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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
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
    private Bitmap logoBitmap = null;
    
    private int activeColorGradeMode = 0; // 0=Shadow, 1=Mid, 2=High
    
    private long lastHistogramUpdate = 0;
    private static final int HISTOGRAM_UPDATE_INTERVAL_MS = 66;
    private boolean isMenuOpen = false;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String PREFS_NAME = "CamulatorPrefs";
    private static final String PREFS_PRESETS = "CamulatorPresets";
    
    private final List<TextView> focalViews = new ArrayList<>();
    
    // Manual State
    private Range<Long> exposureTimeRange;
    private Range<Integer> isoRange;
    private Float minFocusDist = 0f;
    
    // Logo Picker
    private ActivityResultLauncher<String> pickLogoLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        // Initialize Logo Picker
        pickLogoLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                loadAndSaveLogo(uri);
            }
        });

        hideSystemUI();
        setupUI(); 
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
        editor.putInt("logo_radius", binding.seekLogoRadius.getProgress());
        editor.putInt("aspect_ratio_mode", currentAspectRatioMode);
        
        // Watermark flags
        editor.putBoolean("wm_logo", binding.swLogo.isChecked());
        editor.putBoolean("wm_date", binding.swDate.isChecked());
        editor.putBoolean("wm_gps", binding.swGPS.isChecked());
        editor.putBoolean("wm_city", binding.cbCity.isChecked());
        editor.putBoolean("wm_district", binding.cbDistrict.isChecked());
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

        int logoRadius = prefs.getInt("logo_radius", 0);
        binding.seekLogoRadius.setProgress(logoRadius);
        binding.tvLogoRadiusLabel.setText(String.format(Locale.US, "Logo Roundness: %d%%", logoRadius * 2)); // 0-50 maps to 0-100 visual

        currentAspectRatioMode = prefs.getInt("aspect_ratio_mode", AR_4_3);
        updateRatioButtons();
        
        // Watermark Flags
        binding.swLogo.setChecked(prefs.getBoolean("wm_logo", true));
        binding.swDate.setChecked(prefs.getBoolean("wm_date", true));
        binding.swGPS.setChecked(prefs.getBoolean("wm_gps", true));
        binding.cbCity.setChecked(prefs.getBoolean("wm_city", true));
        binding.cbDistrict.setChecked(prefs.getBoolean("wm_district", true));
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
        
        // Load Logo
        loadSavedLogo();
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

        // Aspect Ratio Buttons
        binding.btnRatio169.setOnClickListener(v -> setAspectRatio(AR_16_9));
        binding.btnRatio43.setOnClickListener(v -> setAspectRatio(AR_4_3));
        binding.btnRatio11.setOnClickListener(v -> setAspectRatio(AR_1_1));
        
        binding.btnEdit.setOnClickListener(v -> toggleEditPanel());
        
        // --- Exposure & Focus Sliders ---
        
        // EV Slider
        binding.sliderEV.addOnChangeListener((slider, value, fromUser) -> updateCameraExposure());
        
        // Shutter Slider
        binding.sliderS.addOnChangeListener((slider, value, fromUser) -> updateCameraExposure());

        // Focus Slider - Manual Focus Logic
        binding.sliderF.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                updateManualFocus(value);
            }
        });

        // Auto Focus Reset Button - Switches back to AF
        binding.btnResetFocus.setOnClickListener(v -> {
            if (camera == null) return;
            camera.getCameraControl().cancelFocusAndMetering();
            binding.btnResetFocus.setVisibility(View.INVISIBLE);
            binding.tvValF.setText("AF");
            binding.sliderF.setValue(0f);
        });
        
        // Double Tap Resets
        setupSliderDoubleTap(binding.sliderEV, 0.0f);
        setupSliderDoubleTap(binding.sliderS, 0.0f);
        setupSliderDoubleTap(binding.sliderF, 0.0f); // Double tap on Focus slider resets to 0 (Infinity) and triggers Auto via logic in setupSliderDoubleTap
        
        // Touch to Focus
        binding.viewFinder.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (camera != null) {
                    float x = event.getX();
                    float y = event.getY();
                    MeteringPointFactory factory = binding.viewFinder.getMeteringPointFactory();
                    MeteringPoint point = factory.createPoint(x, y);
                    FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
                    camera.getCameraControl().startFocusAndMetering(action);
                    
                    // Show Reset/Auto button so user can unlock focus
                    binding.btnResetFocus.setVisibility(View.VISIBLE);
                    binding.tvValF.setText("AF");
                    // Reset slider visual to 0 to indicate not in manual override, 
                    // but don't trigger manual focus logic (fromUser check handles this)
                    binding.sliderF.setValue(0f);
                    
                    showFocusIndicator(x, y);
                }
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private void updateCameraExposure() {
        if (camera == null) return;
        
        float evValue = binding.sliderEV.getValue();
        float shutterValue = binding.sliderS.getValue(); // 0 to 100
        
        Camera2CameraControl c2 = Camera2CameraControl.from(camera.getCameraControl());
        CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
        
        if (shutterValue == 0f) {
            // --- AUTO MODE ---
            // Shutter is Auto. EV Slider controls Exposure Compensation.
            binding.tvValS.setText("Auto");
            
            // Set AE Mode ON
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            
            // Apply EV Compensation
            int evIndex = (int) evValue;
            // Clamp to valid range
            ExposureState state = camera.getCameraInfo().getExposureState();
            Range<Integer> range = state.getExposureCompensationRange();
            if (range.contains(evIndex)) {
                camera.getCameraControl().setExposureCompensationIndex(evIndex);
            }
            binding.tvValEV.setText((evIndex > 0 ? "+" : "") + evIndex);
            
        } else {
            // --- MANUAL SHUTTER MODE ---
            // Shutter is Manual. EV Slider controls ISO (Sensitivity).
            
            // Calculate Shutter Time
            if (exposureTimeRange != null) {
                double pct = shutterValue / 100.0;
                long min = Math.max(exposureTimeRange.getLower(), 100000L); 
                long max = Math.min(exposureTimeRange.getUpper(), 1000000000L); // Cap at 1s for usability
                double timeNs = min * Math.pow((double)max / min, pct);
                long finalTime = (long) timeNs;
                
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, finalTime);
                
                // Calculate ISO from EV Slider (-10 to 10)
                // Map EV range to a reasonable ISO range (e.g., 100 to 6400)
                // Base ISO at EV 0 = 640. 
                int baseIso = 640;
                // Simple power of 2 mapping roughly
                int targetIso = (int) (baseIso * Math.pow(1.4, evValue)); // 1.4 approx sqrt(2) per stop
                
                // Clamp ISO
                if (isoRange != null) {
                    targetIso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), targetIso));
                } else {
                    targetIso = Math.max(100, Math.min(6400, targetIso)); // Fallback
                }
                
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, targetIso);
                
                updateShutterLabel(finalTime);
                binding.tvValEV.setText("ISO " + targetIso);
            }
        }
        
        c2.setCaptureRequestOptions(builder.build());
    }
    
    private void updateManualFocus(float sliderValue) {
        if (camera == null) return;
        
        // Map slider (0.0 - 1.0) to focus distance (0 - minFocusDist)
        // 0.0 is Infinity (Diopters 0), 1.0 is Closest
        float dist = sliderValue * (minFocusDist != null ? minFocusDist : 0f);
        
        Camera2CameraControl c2 = Camera2CameraControl.from(camera.getCameraControl());
        CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
        
        // Enforce Manual Focus Mode
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, dist);
        
        c2.setCaptureRequestOptions(builder.build());
        
        binding.tvValF.setText(String.format(Locale.US, "%.1f", dist));
        binding.btnResetFocus.setVisibility(View.VISIBLE);
    }
    
    // --- Editor Setup & Events ---
    
    private void setupEditorUI() {
        // ... existing listeners for tabs ...
        binding.tabCurves.setOnClickListener(v -> showEditorTab(0));
        binding.tabLight.setOnClickListener(v -> showEditorTab(1));
        binding.tabColor.setOnClickListener(v -> showEditorTab(2));
        binding.tabWatermark.setOnClickListener(v -> showEditorTab(3));

        // Curve Channels
        binding.channelRGB.setOnClickListener(v -> binding.curveView.setActiveChannel(CurveView.Channel.RGB));
        binding.channelR.setOnClickListener(v -> binding.curveView.setActiveChannel(CurveView.Channel.RED));
        binding.channelG.setOnClickListener(v -> binding.curveView.setActiveChannel(CurveView.Channel.GREEN));
        binding.channelB.setOnClickListener(v -> binding.curveView.setActiveChannel(CurveView.Channel.BLUE));
        
        binding.curveView.setOnCurveChangeListener(() -> previewCurves = binding.curveView.getControlPointsCopy());

        // Precision Slider
        binding.seekPrecision.setOnSeekBarChangeListener(new SimpleSeekListener(p -> {
            binding.curveView.setPrecisionLevel(p);
            binding.tvPrecisionLabel.setText("Precision: " + p);
        }));

        // Reset Modules
        binding.btnResetCurves.setOnClickListener(v -> {
            binding.curveView.resetActiveChannel();
            previewCurves = binding.curveView.getControlPointsCopy();
        });
        
        binding.btnResetLight.setOnClickListener(v -> {
            editParams.highlights = 0; binding.seekHighlights.setProgress(100);
            editParams.shadows = 0; binding.seekShadows.setProgress(100);
            editParams.whites = 0; binding.seekWhites.setProgress(100);
            editParams.blacks = 0; binding.seekBlacks.setProgress(100);
        });
        
        binding.btnResetColor.setOnClickListener(v -> {
            editParams.shadowHue = 0; editParams.shadowSat = 0;
            editParams.midHue = 0; editParams.midSat = 0;
            editParams.highlightHue = 0; editParams.highlightSat = 0;
            binding.colorWheel.reset();
            binding.colorInfo.setText("Neutral");
        });
        
        // Slider Double Tap Reset (Editor Sliders)
        setupDoubleTapForSeekBar(binding.seekHighlights, 100);
        setupDoubleTapForSeekBar(binding.seekShadows, 100);
        setupDoubleTapForSeekBar(binding.seekWhites, 100);
        setupDoubleTapForSeekBar(binding.seekBlacks, 100);
        
        // Color Grade Modes
        binding.btnGradeShadows.setOnClickListener(v -> setColorGradeMode(0));
        binding.btnGradeMids.setOnClickListener(v -> setColorGradeMode(1));
        binding.btnGradeHighs.setOnClickListener(v -> setColorGradeMode(2));
        setColorGradeMode(0);

        binding.colorWheel.setOnColorChangeListener((hue, sat) -> {
            if (activeColorGradeMode == 0) { editParams.shadowHue = hue; editParams.shadowSat = sat; }
            else if (activeColorGradeMode == 1) { editParams.midHue = hue; editParams.midSat = sat; }
            else { editParams.highlightHue = hue; editParams.highlightSat = sat; }
            binding.colorInfo.setText(String.format(Locale.US, "H: %.0f  S: %.2f", hue, sat));
        });

        // Light Sliders Logic
        binding.seekHighlights.setOnSeekBarChangeListener(new SimpleSeekListener(p -> editParams.highlights = p - 100));
        binding.seekShadows.setOnSeekBarChangeListener(new SimpleSeekListener(p -> editParams.shadows = p - 100));
        binding.seekWhites.setOnSeekBarChangeListener(new SimpleSeekListener(p -> editParams.whites = p - 100));
        binding.seekBlacks.setOnSeekBarChangeListener(new SimpleSeekListener(p -> editParams.blacks = p - 100));

        // Watermark Controls
        setupWatermarkUI();
        
        // Presets
        binding.btnSavePreset.setOnClickListener(v -> showSavePresetDialog());
        binding.btnLoadPreset.setOnClickListener(v -> showLoadPresetDialog());
    }
    
    // Generic Double Tap Helper for Material Sliders
    private void setupSliderDoubleTap(Slider slider, float defaultValue) {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                slider.setValue(defaultValue);
                // Trigger listener if needed, but setValue usually triggers change listener
                if (slider == binding.sliderF) {
                    binding.btnResetFocus.performClick(); // Specific logic for Focus to return to Auto
                }
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true; // Crucial for gesture detector to receive following events
            }
        });
        
        slider.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            return false; // Propagate event so slider still slides
        });
    }

    // Helper for regular SeekBars (Editor)
    private void setupDoubleTapForSeekBar(SeekBar seekBar, int defaultValue) {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                seekBar.setProgress(defaultValue);
                return true;
            }
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });
        seekBar.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            return false;
        });
    }

    private void showEditorTab(int index) {
        binding.containerCurves.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        binding.containerLight.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        binding.containerColor.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        binding.containerWatermark.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        
        int color = Color.parseColor("#FF9800");
        int white = Color.WHITE;
        
        binding.tabCurves.setTextColor(index == 0 ? color : white);
        binding.tabLight.setTextColor(index == 1 ? color : white);
        binding.tabColor.setTextColor(index == 2 ? color : white);
        binding.tabWatermark.setTextColor(index == 3 ? color : white);
    }
    
    private void setColorGradeMode(int mode) {
        activeColorGradeMode = mode;
        binding.btnGradeShadows.setTextColor(mode == 0 ? Color.parseColor("#FF9800") : Color.WHITE);
        binding.btnGradeMids.setTextColor(mode == 1 ? Color.parseColor("#FF9800") : Color.WHITE);
        binding.btnGradeHighs.setTextColor(mode == 2 ? Color.parseColor("#FF9800") : Color.WHITE);
    }

    private void setupWatermarkUI() {
        binding.seekHeight.setOnSeekBarChangeListener(new SimpleSeekListener(p -> {
            binding.tvWatermarkSizeLabel.setText(String.format(Locale.US, "Size: %.1f%%", p / 10f));
        }));
        
        binding.seekLogoRadius.setOnSeekBarChangeListener(new SimpleSeekListener(p -> {
            binding.tvLogoRadiusLabel.setText(String.format(Locale.US, "Logo Roundness: %d%%", p * 2));
        }));
        
        binding.alignLeft.setOnClickListener(v -> updateAlignUI(0));
        binding.alignCenter.setOnClickListener(v -> updateAlignUI(1));
        binding.alignRight.setOnClickListener(v -> updateAlignUI(2));
        
        binding.btnSelectLogo.setOnClickListener(v -> pickLogoLauncher.launch("image/*"));
    }
    
    private void updateAlignUI(int align) {
        currentAlign = align;
        int active = Color.WHITE;
        int inactive = 0xFF888888;
        binding.alignLeft.setColorFilter(align == 0 ? active : inactive);
        binding.alignCenter.setColorFilter(align == 1 ? active : inactive);
        binding.alignRight.setColorFilter(align == 2 ? active : inactive);
    }
    
    // --- Logo Handling ---
    
    private void loadAndSaveLogo(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            
            if (bitmap != null) {
                // Save to internal storage
                File logoFile = new File(getFilesDir(), "custom_logo.png");
                FileOutputStream fos = new FileOutputStream(logoFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                
                logoBitmap = bitmap;
                binding.tvLogoPath.setText("Custom Logo Set");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void loadSavedLogo() {
        File logoFile = new File(getFilesDir(), "custom_logo.png");
        if (logoFile.exists()) {
            logoBitmap = BitmapFactory.decodeFile(logoFile.getAbsolutePath());
            binding.tvLogoPath.setText("Custom Logo Loaded");
        } else {
            binding.tvLogoPath.setText("No Logo Selected");
        }
    }

    // --- Preset Handling ---

    private void showSavePresetDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Preset Name");
        new AlertDialog.Builder(this)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> savePreset(input.getText().toString()))
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void savePreset(String name) {
        if (name.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_PRESETS, Context.MODE_PRIVATE);
            JSONObject json = new JSONObject();
            
            // Save Light Params
            json.put("high", editParams.highlights);
            json.put("shad", editParams.shadows);
            json.put("wht", editParams.whites);
            json.put("blk", editParams.blacks);
            
            // Save Color Params
            json.put("sH", editParams.shadowHue); json.put("sS", editParams.shadowSat);
            json.put("mH", editParams.midHue); json.put("mS", editParams.midSat);
            json.put("hH", editParams.highlightHue); json.put("hS", editParams.highlightSat);
            
            // Save Curves
            Map<CurveView.Channel, List<PointF>> curves = binding.curveView.getControlPointsCopy();
            JSONObject curveJson = new JSONObject();
            for(CurveView.Channel ch : curves.keySet()) {
                JSONArray pointsArr = new JSONArray();
                for(PointF p : curves.get(ch)) {
                    JSONObject pt = new JSONObject();
                    pt.put("x", p.x);
                    pt.put("y", p.y);
                    pointsArr.put(pt);
                }
                curveJson.put(ch.name(), pointsArr);
            }
            json.put("curves", curveJson);
            
            prefs.edit().putString(name, json.toString()).apply();
            Toast.makeText(this, "Preset Saved", Toast.LENGTH_SHORT).show();
            
        } catch (JSONException e) {
            Toast.makeText(this, "Error Saving", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showLoadPresetDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_PRESETS, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        if (all.isEmpty()) {
            Toast.makeText(this, "No Presets Found", Toast.LENGTH_SHORT).show();
            return;
        }
        
        final String[] names = all.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Load Preset")
            .setItems(names, (dialog, which) -> loadPreset(names[which], (String) all.get(names[which])))
            .show();
    }
    
    private void loadPreset(String name, String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            
            // Load Light
            editParams.highlights = json.optInt("high", 0);
            editParams.shadows = json.optInt("shad", 0);
            editParams.whites = json.optInt("wht", 0);
            editParams.blacks = json.optInt("blk", 0);
            
            binding.seekHighlights.setProgress(editParams.highlights + 100);
            binding.seekShadows.setProgress(editParams.shadows + 100);
            binding.seekWhites.setProgress(editParams.whites + 100);
            binding.seekBlacks.setProgress(editParams.blacks + 100);
            
            // Load Color
            editParams.shadowHue = (float) json.optDouble("sH", 0); editParams.shadowSat = (float) json.optDouble("sS", 0);
            editParams.midHue = (float) json.optDouble("mH", 0); editParams.midSat = (float) json.optDouble("mS", 0);
            editParams.highlightHue = (float) json.optDouble("hH", 0); editParams.highlightSat = (float) json.optDouble("hS", 0);
            setColorGradeMode(activeColorGradeMode); // Refresh UI
            
            // Load Curves
            JSONObject curveJson = json.optJSONObject("curves");
            if (curveJson != null) {
                Map<CurveView.Channel, List<PointF>> loadedCurves = new HashMap<>();
                Iterator<String> keys = curveJson.keys();
                while(keys.hasNext()) {
                    String chName = keys.next();
                    CurveView.Channel ch = CurveView.Channel.valueOf(chName);
                    JSONArray pts = curveJson.getJSONArray(chName);
                    List<PointF> list = new ArrayList<>();
                    for(int i=0; i<pts.length(); i++) {
                        JSONObject pt = pts.getJSONObject(i);
                        list.add(new PointF((float)pt.getDouble("x"), (float)pt.getDouble("y")));
                    }
                    loadedCurves.put(ch, list);
                }
                binding.curveView.setControlPoints(loadedCurves);
                previewCurves = loadedCurves;
            }
            
            Toast.makeText(this, "Loaded " + name, Toast.LENGTH_SHORT).show();
            
        } catch (JSONException e) {
            Toast.makeText(this, "Error Loading", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showFocusIndicator(float x, float y) {
        binding.focusIndicator.setX(x - binding.focusIndicator.getWidth() / 2f);
        binding.focusIndicator.setY(y - binding.focusIndicator.getHeight() / 2f);
        binding.focusIndicator.setAlpha(1f);
        binding.focusIndicator.setScaleX(1.5f);
        binding.focusIndicator.setScaleY(1.5f);
        
        binding.focusIndicator.animate()
            .scaleX(1f).scaleY(1f).alpha(0f)
            .setDuration(1000)
            .start();
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
        binding.tvValS.setText(label);
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

    private void setAspectRatio(int mode) {
        if (currentAspectRatioMode == mode) return;
        currentAspectRatioMode = mode;
        updateRatioButtons();
        updatePreviewLayout();
        startCamera(); 
    }
    
    private void updateRatioButtons() {
        binding.btnRatio169.setTextColor(currentAspectRatioMode == AR_16_9 ? Color.parseColor("#FF9800") : Color.WHITE);
        binding.btnRatio43.setTextColor(currentAspectRatioMode == AR_4_3 ? Color.parseColor("#FF9800") : Color.WHITE);
        binding.btnRatio11.setTextColor(currentAspectRatioMode == AR_1_1 ? Color.parseColor("#FF9800") : Color.WHITE);
    }
    
    private void updatePreviewLayout() {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) binding.viewFinder.getLayoutParams();
        ConstraintLayout.LayoutParams overlayParams = (ConstraintLayout.LayoutParams) binding.previewOverlay.getLayoutParams();
        ConstraintLayout.LayoutParams shutterParams = (ConstraintLayout.LayoutParams) binding.shutterOverlay.getLayoutParams();
        
        String ratioString;
        ImageView.ScaleType scaleType;

        switch (currentAspectRatioMode) {
            case AR_16_9: 
                ratioString = "H,9:16"; 
                scaleType = ImageView.ScaleType.CENTER_CROP; // Hardware preview is usually 16:9, matching is easy
                break;
            case AR_1_1: 
                ratioString = "H,1:1"; 
                scaleType = ImageView.ScaleType.CENTER_CROP;
                break;
            case AR_4_3: 
            default: 
                ratioString = "H,3:4"; 
                scaleType = ImageView.ScaleType.FIT_CENTER; // Standard photo size
                break;
        }
        
        params.dimensionRatio = ratioString;
        overlayParams.dimensionRatio = ratioString;
        shutterParams.dimensionRatio = ratioString;
        
        binding.viewFinder.setLayoutParams(params);
        binding.viewFinder.setScaleType(androidx.camera.view.PreviewView.ScaleType.FIT_CENTER);
        
        binding.previewOverlay.setLayoutParams(overlayParams);
        binding.previewOverlay.setScaleType(scaleType);
        
        binding.shutterOverlay.setLayoutParams(shutterParams);
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

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(targetAspectRatio)
                .build();
        preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(targetAspectRatio)
                .build();
                
        // Real-time processing setup
        // IMPORTANT: targetAspectRatio MUST match imageCapture to ensure WYSIWYG
        ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetAspectRatio(targetAspectRatio);
        
        // Use ResolutionSelector for finer control if available, or fallback to default aspect ratio matching
        // In this setup, matching AspectRatio is critical for overlay alignment.
        
        imageAnalysis = analysisBuilder.build();
                
        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            // 1. Convert to Bitmap
            Bitmap bmp = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());

            // 2. Rotate if needed
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (bmp != rotated) bmp.recycle();
                bmp = rotated;
            }
            
            // 3. Apply Real-time Effects
            ImageProcessor.applyProcessing(bmp, previewCurves, editParams);
            
            // 4. Update UI
            final Bitmap finalBmp = bmp;
            long currentTime = System.currentTimeMillis();
            
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
            
            // Get Characteristics
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            exposureTimeRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            isoRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            minFocusDist = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            
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
            
            // Reset Sliders for new camera binding
            binding.sliderEV.setValue(0f);
            binding.sliderS.setValue(0f);
            binding.sliderF.setValue(0f);
            binding.tvValEV.setText("0");
            binding.tvValS.setText("Auto");
            binding.tvValF.setText("AF");
            
            // Init EV slider range based on capabilities
            ExposureState state = camera.getCameraInfo().getExposureState();
            Range<Integer> range = state.getExposureCompensationRange();
            binding.sliderEV.setValueFrom(range.getLower());
            binding.sliderEV.setValueTo(range.getUpper());
            binding.sliderEV.setStepSize(1f);
            
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
        wmConfig.showDistrict = binding.cbDistrict.isChecked();
        wmConfig.showStreet = binding.cbStreet.isChecked();
        wmConfig.logoBitmap = logoBitmap; // Pass Custom Logo
        wmConfig.logoCornerRadiusPercent = binding.seekLogoRadius.getProgress() / 100f; // 0 to 0.5
        
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
                    
                    // City
                    String city = addr.getSubAdminArea();
                    if (city == null) city = addr.getLocality();
                    if (city == null) city = addr.getAdminArea();
                    
                    config.cityText = addr.getLocality();
                    if (config.cityText == null) config.cityText = addr.getAdminArea();
                    
                    config.districtText = addr.getSubAdminArea();
                    
                    config.streetText = addr.getThoroughfare(); 
                    if (config.streetText == null) config.streetText = addr.getFeatureName(); 
                    
                    // Fallback cleanup
                    if (config.cityText == null) config.cityText = "";
                    if (config.districtText == null) config.districtText = "";
                    if (config.streetText == null) config.streetText = "";
                }
            } catch (Exception e) {}
        } else {
            config.gpsStr = "";
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
    
    // Helper listener
    private static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        interface OnChange { void onProgress(int p); }
        private final OnChange callback;
        SimpleSeekListener(OnChange c) { callback = c; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if(f) callback.onProgress(p); }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}