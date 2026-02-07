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
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.SizeF;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
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

    private static final float FULL_FRAME_DIAGONAL = 43.2666f;
    private float baseEquivalentFocalLength = 24.0f; 
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
        
        findViewById(R.id.btnWatermark).setOnClickListener(v -> showWatermarkSettingsDialog());
    }

    private void showWatermarkSettingsDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.dialog_watermark_settings);

        Switch swEnabled = dialog.findViewById(R.id.swWatermarkEnabled);
        Switch swLogo = dialog.findViewById(R.id.swShowLogo);
        EditText etText = dialog.findViewById(R.id.etCustomText);
        Switch swTime = dialog.findViewById(R.id.swShowTime);
        Switch swCoords = dialog.findViewById(R.id.swShowCoords);
        Switch swPlace = dialog.findViewById(R.id.swShowPlace);
        RadioGroup rgSize = dialog.findViewById(R.id.rgTextSize);
        RadioGroup rgPos = dialog.findViewById(R.id.rgPosition);

        if (swEnabled != null) {
            swEnabled.setChecked(wmConfig.enabled);
            swLogo.setChecked(wmConfig.showLogo);
            etText.setText(wmConfig.customText);
            swTime.setChecked(wmConfig.showTime);
            swCoords.setChecked(wmConfig.showCoords);
            swPlace.setChecked(wmConfig.showPlace);

            switch (wmConfig.textSize) {
                case 0: rgSize.check(R.id.rbSmall); break;
                case 1: rgSize.check(R.id.rbMedium); break;
                case 2: rgSize.check(R.id.rbLarge); break;
            }
            
            switch (wmConfig.position) {
                case 0: rgPos.check(R.id.rbPosLeft); break;
                case 1: rgPos.check(R.id.rbPosCenter); break;
                case 2: rgPos.check(R.id.rbPosRight); break;
            }

            dialog.setOnDismissListener(d -> {
                wmConfig.enabled = swEnabled.isChecked();
                wmConfig.showLogo = swLogo.isChecked();
                wmConfig.customText = etText.getText().toString();
                wmConfig.showTime = swTime.isChecked();
                wmConfig.showCoords = swCoords.isChecked();
                wmConfig.showPlace = swPlace.isChecked();

                int selectedId = rgSize.getCheckedRadioButtonId();
                if (selectedId == R.id.rbSmall) wmConfig.textSize = 0;
                else if (selectedId == R.id.rbMedium) wmConfig.textSize = 1;
                else if (selectedId == R.id.rbLarge) wmConfig.textSize = 2;
                
                int posId = rgPos.getCheckedRadioButtonId();
                if (posId == R.id.rbPosLeft) wmConfig.position = 0;
                else if (posId == R.id.rbPosCenter) wmConfig.position = 1;
                else if (posId == R.id.rbPosRight) wmConfig.position = 2;
                
                if (wmConfig.showPlace && (wmConfig.placeName == null || wmConfig.placeName.isEmpty())) {
                    // Trigger update if enabled but empty
                    updateLocation();
                }

                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
            });
        }

        dialog.show();
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

        float targetRatio = targetEquivalentMm / baseEquivalentFocalLength;
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

                calculateBaseFocalLength();
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
            
            SizeF sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            
            if (sensorSize != null && focalLengths != null && focalLengths.length > 0) {
                float w = sensorSize.getWidth();
                float h = sensorSize.getHeight();
                float sensorDiagonal = (float) Math.sqrt(w * w + h * h);
                float cropFactor = FULL_FRAME_DIAGONAL / sensorDiagonal;
                baseEquivalentFocalLength = focalLengths[0] * cropFactor;
            }
        } catch (Exception e) {
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
                cameraExecutor.execute(() -> {
                    Bitmap processed = ImageUtils.processImage(bitmap, currentFilter, curveView.getPoints(), wmConfig);
                    saveImage(processed);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved to Gallery", Toast.LENGTH_SHORT).show());
                });
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
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "CAM_" + System.currentTimeMillis() + ".jpg");
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camulator");
        }

        try {
            OutputStream stream = getContentResolver().openOutputStream(
                    getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            );
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            if (stream != null) stream.close();
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error Saving Image", Toast.LENGTH_SHORT).show());
        }
    }
    
    private void updateLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    wmConfig.latLng = String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
                    
                    // Geocoding in background
                    cameraExecutor.execute(() -> {
                        Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                        try {
                            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                            if (addresses != null && !addresses.isEmpty()) {
                                Address addr = addresses.get(0);
                                // Try to get Locality (City) or Admin Area (State)
                                String place = addr.getLocality();
                                if (place == null) place = addr.getSubAdminArea();
                                if (place == null) place = addr.getAdminArea();
                                
                                String country = addr.getCountryName();
                                if (place != null && country != null) {
                                    wmConfig.placeName = place + ", " + country;
                                } else if (country != null) {
                                    wmConfig.placeName = country;
                                } else {
                                    wmConfig.placeName = place;
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }
            });
        }
    }

    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA, 
            Manifest.permission.WRITE_EXTERNAL_STORAGE, 
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION // Added coarse for good measure
    };

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