package com.camulator.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.SizeF;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private PreviewView viewFinder;
    private CurveView curveView;
    private FusedLocationProviderClient fusedLocationClient;
    private Camera camera;
    private LinearLayout focalLengthContainer;

    private ImageUtils.FilterType currentFilter = ImageUtils.FilterType.NONE;
    private ImageUtils.WatermarkConfig wmConfig = new ImageUtils.WatermarkConfig();
    private int currentAspectRatio = AspectRatio.RATIO_4_3;

    // Standard Full Frame Diagonal ~43.27mm (sqrt(36^2 + 24^2))
    private static final float FULL_FRAME_DIAGONAL = 43.2666f;
    
    // Default fallback if calculation fails
    private float baseEquivalentFocalLength = 24.0f; 
    
    // Classic Focal Lengths
    private static final int[] FOCAL_LENGTHS = {16, 24, 28, 35, 50, 75, 85, 105, 135};
    private int selectedFocalLength = 24;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        curveView = findViewById(R.id.curveView);
        focalLengthContainer = findViewById(R.id.focalLengthContainer);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10);
        }

        setupControls();
        // Initialize buttons with default look
        setupFocalLengthButtons();
        cameraExecutor = Executors.newSingleThreadExecutor();
        updateLocation();
    }

    private void setupControls() {
        findViewById(R.id.btnCapture).setOnClickListener(v -> takePhoto());
        
        Button btnRatio = findViewById(R.id.btnRatio);
        btnRatio.setOnClickListener(v -> {
            if (currentAspectRatio == AspectRatio.RATIO_4_3) {
                currentAspectRatio = AspectRatio.RATIO_16_9;
                btnRatio.setText("16:9");
            } else {
                currentAspectRatio = AspectRatio.RATIO_4_3;
                btnRatio.setText("4:3");
            }
            startCamera(); 
        });

        Button btnFilter = findViewById(R.id.btnFilter);
        btnFilter.setOnClickListener(v -> {
            if (currentFilter == ImageUtils.FilterType.NONE) {
                currentFilter = ImageUtils.FilterType.FUJI;
                btnFilter.setText("Fuji");
            } else if (currentFilter == ImageUtils.FilterType.FUJI) {
                currentFilter = ImageUtils.FilterType.LEICA;
                btnFilter.setText("Leica");
            } else if (currentFilter == ImageUtils.FilterType.LEICA) {
                currentFilter = ImageUtils.FilterType.BW;
                btnFilter.setText("B&W");
            } else {
                currentFilter = ImageUtils.FilterType.NONE;
                btnFilter.setText("Normal");
            }
        });

        Button btnCurve = findViewById(R.id.btnCurve);
        btnCurve.setOnClickListener(v -> {
            if (curveView.getVisibility() == View.VISIBLE) {
                curveView.setVisibility(View.GONE);
            } else {
                curveView.setVisibility(View.VISIBLE);
            }
        });
        
        findViewById(R.id.btnWatermark).setOnClickListener(v -> {
            wmConfig.textSize = (wmConfig.textSize + 1) % 3;
            String sizeLabel = wmConfig.textSize == 0 ? "S" : wmConfig.textSize == 1 ? "M" : "L";
            Toast.makeText(this, "WM Size: " + sizeLabel, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupFocalLengthButtons() {
        focalLengthContainer.removeAllViews();
        for (int focalLength : FOCAL_LENGTHS) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(focalLength + "mm");
            btn.setTextColor(focalLength == selectedFocalLength ? Color.YELLOW : Color.WHITE);
            btn.setTextSize(13);
            btn.setPadding(30, 10, 30, 10);
            btn.setMinimumWidth(0);
            btn.setMinWidth(0);
            btn.setBackgroundColor(Color.TRANSPARENT);
            
            btn.setOnClickListener(v -> {
                selectedFocalLength = focalLength;
                applyFocalLengthZoom(focalLength);
                updateFocalLengthUI();
            });
            
            focalLengthContainer.addView(btn);
        }
    }

    private void updateFocalLengthUI() {
        for (int i = 0; i < focalLengthContainer.getChildCount(); i++) {
            Button btn = (Button) focalLengthContainer.getChildAt(i);
            int fl = FOCAL_LENGTHS[i];
            
            // Highlight selected
            if (fl == selectedFocalLength) {
                btn.setTextColor(Color.YELLOW);
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                btn.setTextColor(Color.WHITE);
                btn.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void applyFocalLengthZoom(int targetEquivalentMm) {
        if (camera == null) return;
        
        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
        if (zoomState == null) return;

        // Formula: Zoom Ratio = Target Equivalent / Base Equivalent
        float targetRatio = targetEquivalentMm / baseEquivalentFocalLength;
        
        // Clamp to device capabilities
        // Note: minZoomRatio might be < 1.0 if the device has an ultra-wide lens exposed
        float min = zoomState.getMinZoomRatio();
        float max = zoomState.getMaxZoomRatio();
        
        float finalRatio = Math.max(min, Math.min(max, targetRatio));
        
        camera.getCameraControl().setZoomRatio(finalRatio);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(currentAspectRatio)
                        .build();

                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setTargetAspectRatio(currentAspectRatio)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                // Calculate base focal length after binding
                calculateBaseFocalLength();

                // Apply initial focal length
                applyFocalLengthZoom(selectedFocalLength);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera init failed", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void calculateBaseFocalLength() {
        try {
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            
            // 1. Get Physical Sensor Size
            SizeF sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            
            // 2. Get Physical Focal Length (usually index 0 is the main/widest on single-lens logical cams)
            float[] focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            
            if (sensorSize != null && focalLengths != null && focalLengths.length > 0) {
                float w = sensorSize.getWidth();
                float h = sensorSize.getHeight();
                float sensorDiagonal = (float) Math.sqrt(w * w + h * h);
                
                // 3. Calculate Crop Factor
                float cropFactor = FULL_FRAME_DIAGONAL / sensorDiagonal;
                
                // 4. Calculate Equivalent Focal Length
                baseEquivalentFocalLength = focalLengths[0] * cropFactor;
                
                // Debug toast
                // String msg = String.format(Locale.US, "Base: %.1fmm (Phy: %.1fmm)", baseEquivalentFocalLength, focalLengths[0]);
                // Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            // Fallback to 24mm if Camera2 info is inaccessible
            baseEquivalentFocalLength = 24.0f;
            e.printStackTrace();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap = imageProxyToBitmap(image);
                image.close();
                Bitmap processed = ImageUtils.processImage(bitmap, currentFilter, curveView.getPoints(), wmConfig);
                saveImage(processed);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Toast.makeText(MainActivity.this, "Capture Failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        
        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void saveImage(Bitmap bitmap) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "CAM_" + System.currentTimeMillis());
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camulator");
        }

        try {
            OutputStream stream = getContentResolver().openOutputStream(
                    getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            );
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            stream.close();
            Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void updateLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    wmConfig.latLng = String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
                }
            });
        }
    }

    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION};

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
        if (requestCode == 10) {
            if (allPermissionsGranted()) {
                startCamera();
                updateLocation();
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}