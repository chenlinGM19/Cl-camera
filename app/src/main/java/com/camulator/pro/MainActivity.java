package com.camulator.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Size;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
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
import com.camulator.pro.utils.WatermarkUtil;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import android.graphics.PointF;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private FusedLocationProviderClient fusedLocationClient;
    
    // Settings
    private int currentAspectRatio = AspectRatio.RATIO_4_3;
    private float currentZoomRatio = 1f;
    private float minZoomRatio = 1f;
    private float maxZoomRatio = 10f;
    private int currentAlign = 0;
    
    // Performance Control
    private long lastHistogramUpdate = 0;
    private static final long HISTOGRAM_UPDATE_INTERVAL_MS = 66; // ~15 FPS limit
    
    // UI State
    private boolean isMenuOpen = false;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String PREFS_NAME = "CamulatorPrefs";
    
    private final List<TextView> focalViews = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        hideSystemUI();
        loadSettings();

        // Apply saved aspect ratio to UI immediately
        updatePreviewLayout();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupUI();
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
        editor.putBoolean("show_date", binding.cbDate.isChecked());
        editor.putBoolean("show_gps", binding.cbGPS.isChecked());
        editor.putBoolean("show_city", binding.cbCity.isChecked());
        editor.putBoolean("show_street", binding.cbStreet.isChecked());
        editor.putInt("height_progress", binding.seekHeight.getProgress());
        editor.putInt("aspect_ratio", currentAspectRatio);
        
        editor.apply();
    }
    
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        boolean footerMode = prefs.getBoolean("footer_mode", true);
        if (footerMode) binding.rbFooter.setChecked(true); else binding.rbOverlay.setChecked(true);
        
        binding.toggleBgColor.setChecked(prefs.getBoolean("white_bg", true));
        updateAlignUI(prefs.getInt("align", 0));
        binding.etWatermarkText.setText(prefs.getString("custom_text", "Camulator Pro"));
        binding.cbDate.setChecked(prefs.getBoolean("show_date", true));
        binding.cbGPS.setChecked(prefs.getBoolean("show_gps", true));
        binding.cbCity.setChecked(prefs.getBoolean("show_city", true));
        binding.cbStreet.setChecked(prefs.getBoolean("show_street", false));
        binding.seekHeight.setProgress(prefs.getInt("height_progress", 6));
        
        currentAspectRatio = prefs.getInt("aspect_ratio", AspectRatio.RATIO_4_3);
    }

    private void setupUI() {
        binding.btnShutter.setOnClickListener(v -> takePhoto());

        binding.btnGallery.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, GalleryActivity.class));
        });
        
        focalViews.clear();
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
        
        // Default to 24mm (1.0x) visually
        updateFocalLengthSelection(binding.focal24mm);

        binding.btnAspectRatio.setOnClickListener(v -> toggleAspectRatio());
        binding.btnEdit.setOnClickListener(v -> toggleEditPanel());
    }
    
    private void setupEditorUI() {
        // Tab Switching
        binding.tabCurves.setOnClickListener(v -> {
            binding.containerCurves.setVisibility(View.VISIBLE);
            binding.containerWatermark.setVisibility(View.GONE);
            binding.tabCurves.setTextColor(Color.parseColor("#FF9800"));
            binding.tabWatermark.setTextColor(Color.WHITE);
        });
        
        binding.tabWatermark.setOnClickListener(v -> {
            binding.containerCurves.setVisibility(View.GONE);
            binding.containerWatermark.setVisibility(View.VISIBLE);
            binding.tabCurves.setTextColor(Color.WHITE);
            binding.tabWatermark.setTextColor(Color.parseColor("#FF9800"));
        });

        // Curve Channels
        View.OnClickListener channelListener = v -> {
            binding.channelRGB.setTextColor(Color.WHITE);
            binding.channelR.setTextColor(Color.parseColor("#FF4444"));
            binding.channelG.setTextColor(Color.parseColor("#44FF44"));
            binding.channelB.setTextColor(Color.parseColor("#4444FF"));
            
            ((TextView)v).setTextColor(Color.parseColor("#FF9800")); // Highlight Active
            
            if (v == binding.channelRGB) binding.curveView.setActiveChannel(CurveView.Channel.RGB);
            else if (v == binding.channelR) binding.curveView.setActiveChannel(CurveView.Channel.RED);
            else if (v == binding.channelG) binding.curveView.setActiveChannel(CurveView.Channel.GREEN);
            else if (v == binding.channelB) binding.curveView.setActiveChannel(CurveView.Channel.BLUE);
        };
        
        binding.channelRGB.setOnClickListener(channelListener);
        binding.channelR.setOnClickListener(channelListener);
        binding.channelG.setOnClickListener(channelListener);
        binding.channelB.setOnClickListener(channelListener);
        
        // Align Buttons
        binding.alignLeft.setOnClickListener(v -> updateAlignUI(0));
        binding.alignCenter.setOnClickListener(v -> updateAlignUI(1));
        binding.alignRight.setOnClickListener(v -> updateAlignUI(2));
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
        if (currentAspectRatio == AspectRatio.RATIO_4_3) {
            currentAspectRatio = AspectRatio.RATIO_16_9;
            Toast.makeText(this, "Aspect Ratio: 16:9", Toast.LENGTH_SHORT).show();
        } else {
            currentAspectRatio = AspectRatio.RATIO_4_3;
            Toast.makeText(this, "Aspect Ratio: 4:3", Toast.LENGTH_SHORT).show();
        }
        updatePreviewLayout();
        startCamera(); // Re-bind use cases
    }
    
    private void updatePreviewLayout() {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) binding.viewFinder.getLayoutParams();
        if (currentAspectRatio == AspectRatio.RATIO_16_9) {
            // In portrait, 16:9 becomes 9:16
            params.dimensionRatio = "H,9:16";
        } else {
            // In portrait, 4:3 becomes 3:4
            params.dimensionRatio = "H,3:4";
        }
        binding.viewFinder.setLayoutParams(params);
    }
    
    private void toggleEditPanel() {
        isMenuOpen = !isMenuOpen;
        binding.layoutEditor.setVisibility(isMenuOpen ? View.VISIBLE : View.GONE);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                // Handle error
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(currentAspectRatio)
                .build();
        preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(currentAspectRatio)
                .build();
                
        // Real-time Histogram Analysis
        // We use a low resolution for performance (e.g. 640x480)
        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
                
        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            long currentTime = System.currentTimeMillis();
            
            // Throttle UI updates to save battery and main thread cycles
            if (binding.layoutEditor.getVisibility() == View.VISIBLE && 
               (currentTime - lastHistogramUpdate > HISTOGRAM_UPDATE_INTERVAL_MS)) {
                
                int[] histogram = ImageProcessor.calculateLuminanceHistogram(
                        image.getPlanes()[0].getBuffer(), 
                        image.getPlanes()[0].getPixelStride());
                
                lastHistogramUpdate = currentTime;
                runOnUiThread(() -> binding.curveView.setHistogramData(histogram));
            }
            
            image.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        try {
            cameraProvider.unbindAll();
            // Bind analysis as well
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            
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

        // Visual feedback
        binding.shutterOverlay.setVisibility(View.VISIBLE);
        binding.shutterOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            binding.shutterOverlay.setVisibility(View.GONE);
            binding.shutterOverlay.setAlpha(1f);
        });
        binding.btnShutter.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        
        // Show Processing
        binding.processingProgress.setVisibility(View.VISIBLE);
        binding.btnShutter.setEnabled(false);

        File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), 
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // Capture Config
        final WatermarkUtil.WatermarkConfig wmConfig = new WatermarkUtil.WatermarkConfig();
        wmConfig.isFooterMode = binding.rbFooter.isChecked();
        wmConfig.isWhiteBg = binding.toggleBgColor.isChecked();
        wmConfig.align = currentAlign;
        wmConfig.customText = binding.etWatermarkText.getText().toString();
        wmConfig.showDate = binding.cbDate.isChecked();
        wmConfig.showGPS = binding.cbGPS.isChecked();
        wmConfig.showCity = binding.cbCity.isChecked();
        wmConfig.showStreet = binding.cbStreet.isChecked();
        wmConfig.heightPercent = (binding.seekHeight.getProgress() / 100f) + 0.05f; 

        // CRITICAL: Get a deep copy of the curve points on the UI thread NOW.
        // If we access binding.curveView in the background thread later, it might change
        // or cause thread issues.
        final Map<CurveView.Channel, List<PointF>> curveData = binding.curveView.getControlPointsCopy();

        // Get Location safely
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
             imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    cameraExecutor.execute(() -> processAndSaveImage(photoFile, wmConfig, location, curveData));
                }
                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    onCaptureFinished(false);
                    Toast.makeText(MainActivity.this, "Capture Failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }).addOnFailureListener(e -> {
             imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    cameraExecutor.execute(() -> processAndSaveImage(photoFile, wmConfig, null, curveData));
                }
                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    onCaptureFinished(false);
                    Toast.makeText(MainActivity.this, "Capture Failed", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
    
    private void processAndSaveImage(File originalFile, WatermarkUtil.WatermarkConfig config, 
                                     Location location, Map<CurveView.Channel, List<PointF>> curveData) {
        // 1. Load Bitmap as Mutable for In-Place Modification (Saves 50% Memory)
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = true; 
        Bitmap bitmap = BitmapFactory.decodeFile(originalFile.getAbsolutePath(), opts);
        
        if (bitmap == null) {
            onCaptureFinished(false);
            return;
        }
        
        // 2. Extract Exif & Orientation
        try {
            ExifInterface exif = new ExifInterface(originalFile.getAbsolutePath());
            
            // Handle Orientation
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotationDegrees = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotationDegrees = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotationDegrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotationDegrees = 270; break;
            }
            
            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                // We must create a new bitmap for rotation, then recycle old
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                    // Ensure the new one is mutable for next steps? createBitmap with matrix usually returns immutable.
                    if (!bitmap.isMutable()) {
                         Bitmap mutableRotated = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                         bitmap.recycle();
                         bitmap = mutableRotated;
                    }
                }
            }

            // Exif Metadata Construction
            StringBuilder exifBuilder = new StringBuilder();
            
            // Focal Length
            String focalLen = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if (focalLen != null) {
                try {
                    String[] parts = focalLen.split("/");
                    if (parts.length == 2) {
                        float val = Float.parseFloat(parts[0]) / Float.parseFloat(parts[1]);
                        exifBuilder.append(new DecimalFormat("0").format(val)).append("mm");
                    } else {
                        exifBuilder.append(focalLen).append("mm");
                    }
                } catch (Exception e) {
                    exifBuilder.append("24mm"); 
                }
            } else {
                int estimated = (int)(24 * currentZoomRatio);
                exifBuilder.append(estimated).append("mm");
            }

            // Aperture
            String aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
            if (aperture != null) {
                if (exifBuilder.length() > 0) exifBuilder.append(" ");
                try {
                    double f = Double.parseDouble(aperture);
                    exifBuilder.append("f/").append(new DecimalFormat("0.0").format(f));
                } catch (NumberFormatException e) {
                    exifBuilder.append("f/").append(aperture);
                }
            } else {
                exifBuilder.append(" f/1.8"); 
            }

            // Shutter Speed
            String exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposure != null) {
                if (exifBuilder.length() > 0) exifBuilder.append(" ");
                try {
                    double sec = Double.parseDouble(exposure);
                    if (sec < 1.0) {
                        exifBuilder.append("1/").append(Math.round(1.0/sec)).append("s");
                    } else {
                        exifBuilder.append(sec).append("s");
                    }
                } catch (Exception e) {
                     exifBuilder.append(exposure).append("s");
                }
            }
            
            // ISO
            String iso = exif.getAttribute(ExifInterface.TAG_ISO);
            if (iso != null) {
                if (exifBuilder.length() > 0) exifBuilder.append(" ");
                exifBuilder.append("ISO").append(iso);
            }
            
            config.exifInfo = exifBuilder.toString();
            
        } catch (IOException e) {
            config.exifInfo = "";
        }

        // 3. Apply Curves (In-Place) using the captured curve data
        bitmap = ImageProcessor.applyCurves(bitmap, curveData);

        // 4. Prepare Other Metadata
        config.dateStr = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date());
        
        StringBuilder locBuilder = new StringBuilder();
        if (location != null) {
            if (config.showGPS) {
                locBuilder.append(String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude()));
            }
            
            if (config.showCity || config.showStreet) {
                try {
                    Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address addr = addresses.get(0);
                        if (locBuilder.length() > 0) locBuilder.append(" | ");
                        
                        boolean addedCity = false;
                        if (config.showCity) {
                            if (addr.getLocality() != null) {
                                locBuilder.append(addr.getLocality());
                                addedCity = true;
                            } else if (addr.getSubAdminArea() != null) {
                                locBuilder.append(addr.getSubAdminArea());
                                addedCity = true;
                            }
                        }
                        
                        if (config.showStreet && addr.getThoroughfare() != null) {
                            if (addedCity) locBuilder.append(", ");
                            locBuilder.append(addr.getThoroughfare());
                        }
                    }
                } catch (IOException e) {
                    // Ignore geocoder errors
                }
            }
        }
        config.locStr = locBuilder.toString();
        
        // 5. Add Watermark (Creates new Bitmap typically due to canvas resizing)
        Bitmap finalBitmap = WatermarkUtil.addWatermark(bitmap, config);
        
        // Recycle intermediate if it was replaced
        if (bitmap != finalBitmap && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        
        // 6. Save Final
        try (FileOutputStream out = new FileOutputStream(originalFile)) {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            
            Bitmap thumb = Bitmap.createScaledBitmap(finalBitmap, 200, 200, false);
            finalBitmap.recycle();
            
            runOnUiThread(() -> {
                binding.thumbnail.setImageBitmap(thumb);
                onCaptureFinished(true);
            });
            
        } catch (Exception e) {
            e.printStackTrace();
            onCaptureFinished(false);
        }
    }
    
    private void onCaptureFinished(boolean success) {
        runOnUiThread(() -> {
            binding.processingProgress.setVisibility(View.GONE);
            binding.btnShutter.setEnabled(true);
        });
    }

    private boolean allPermissionsGranted() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}