package com.camulator.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.camulator.pro.databinding.ActivityMainBinding;
import com.camulator.pro.processor.ImageProcessor;
import com.camulator.pro.utils.WatermarkUtil;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    
    // Zoom Logic
    private float currentZoomRatio = 1f;
    private float minZoomRatio = 1f;
    private float maxZoomRatio = 10f;
    
    // UI State
    private boolean isMenuOpen = false;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        // Hide system UI for immersive mode
        hideSystemUI();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupUI();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void setupUI() {
        // Shutter Button
        binding.btnShutter.setOnClickListener(v -> takePhoto());

        // Gallery Button
        binding.btnGallery.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, GalleryActivity.class));
        });

        // Focal Length Buttons (Simulated Zoom)
        setupFocalLength(binding.focal16mm, 0.5f); // Ultra Wide
        setupFocalLength(binding.focal24mm, 1.0f); // Wide (Standard)
        setupFocalLength(binding.focal35mm, 1.5f);
        setupFocalLength(binding.focal50mm, 2.0f);
        setupFocalLength(binding.focal85mm, 3.5f);

        // Aspect Ratio
        binding.btnAspectRatio.setOnClickListener(v -> toggleAspectRatio());
        
        // Curves/Edit Toggle
        binding.btnEdit.setOnClickListener(v -> toggleEditPanel());
        
        // Curve View Listeners
        binding.curveView.setOnCurveChangeListener(() -> {
            // In a real app, apply curve to preview via OpenGL/Vulkan
            // Here we just store params for the capture
        });
    }

    private void setupFocalLength(View view, float zoom) {
        view.setOnClickListener(v -> {
            if (camera != null) {
                // Check bounds to prevent crash
                float z = Math.max(minZoomRatio, Math.min(zoom, maxZoomRatio));
                camera.getCameraControl().setZoomRatio(z);
                currentZoomRatio = z;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            }
        });
    }

    private void toggleAspectRatio() {
        // Implementation note: Changing aspect ratio requires unbinding and rebinding CameraX use cases
        // with different TargetAspectRatio or ViewPort.
        // Simplified for this code block: Toast.
        Toast.makeText(this, "Aspect Ratio: 4:3 (Default)", Toast.LENGTH_SHORT).show();
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
        
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            
            // Get Zoom capabilities
            if (camera.getCameraInfo().getZoomState().getValue() != null) {
                minZoomRatio = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
                maxZoomRatio = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
            }
            
        } catch (Exception exc) {
            Toast.makeText(this, "Camera init failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        // Visual shutter feedback
        binding.shutterOverlay.setVisibility(View.VISIBLE);
        binding.shutterOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            binding.shutterOverlay.setVisibility(View.GONE);
            binding.shutterOverlay.setAlpha(1f);
        });
        binding.btnShutter.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);

        File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), 
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                // Post-Processing on background thread
                cameraExecutor.execute(() -> processAndSaveImage(photoFile));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Toast.makeText(MainActivity.this, "Capture Failed", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void processAndSaveImage(File originalFile) {
        Bitmap bitmap = BitmapFactory.decodeFile(originalFile.getAbsolutePath());
        
        // 1. Apply Curves & Grading
        Bitmap processed = ImageProcessor.applyCurves(bitmap, binding.curveView.getControlPoints());
        
        // 2. Add Watermark
        // Mock Location
        String locStr = "N 34°01' E 118°41'";
        String dateStr = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date());
        processed = WatermarkUtil.addWatermark(processed, "Camulator Pro", dateStr, locStr);
        
        // 3. Save Final
        try (FileOutputStream out = new FileOutputStream(originalFile)) {
            processed.compress(Bitmap.CompressFormat.JPEG, 100, out);
            
            // Update Thumbnail on UI
            Bitmap thumb = Bitmap.createScaledBitmap(processed, 200, 200, false);
            runOnUiThread(() -> binding.thumbnail.setImageBitmap(thumb));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
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
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}