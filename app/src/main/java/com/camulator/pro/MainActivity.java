package com.camulator.pro;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.hardware.camera2.CameraCharacteristics;
import android.location.Address;
import android.location.Geocoder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private PreviewView viewFinder;
    private ImageView ivEditPreview;
    private CurveView curveView;
    private View presetEditorContainer, controlsContainer;
    private View maskTop, maskBottom;
    private LinearLayout focalLengthContainer, filterContainer, llPresetList;
    private Button btnRatio;
    private SeekBar sbSaturation;

    private FusedLocationProviderClient fusedLocationClient;
    private Camera camera;

    private ImageUtils.FilterType currentFilter = ImageUtils.FilterType.NONE;
    private float currentSaturation = 0f; // -100 to 100
    private ImageUtils.WatermarkConfig wmConfig = new ImageUtils.WatermarkConfig();
    
    // Preset Management
    private List<ImageUtils.CurvePreset> loadedPresets = new ArrayList<>();
    private ImageUtils.CurvePreset currentPreset = new ImageUtils.CurvePreset();
    
    private int aspectRatioMode = 0; // 0=4:3, 1=16:9, 2=1:1
    
    // Freeze Frame Bitmap for Editing
    private Bitmap frozenPreviewBitmap;

    private static final float FULL_FRAME_DIAGONAL = 43.2666f;
    private float baseEquivalentFocalLength = 24.0f; 
    private static final int[] FOCAL_LENGTHS = {16, 24, 28, 35, 50, 75, 85, 105, 135};
    private int selectedFocalLength = 24;
    
    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        ivEditPreview = findViewById(R.id.ivEditPreview);
        curveView = findViewById(R.id.curveView);
        presetEditorContainer = findViewById(R.id.presetEditorContainer);
        controlsContainer = findViewById(R.id.controlsContainer);
        maskTop = findViewById(R.id.maskTop);
        maskBottom = findViewById(R.id.maskBottom);
        focalLengthContainer = findViewById(R.id.focalLengthContainer);
        filterContainer = findViewById(R.id.filterContainer);
        llPresetList = findViewById(R.id.llPresetList);
        btnRatio = findViewById(R.id.btnRatio);
        sbSaturation = findViewById(R.id.sbSaturation);
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        curveView.setOnCurveChangeListener(this::updateFreezeFramePreview);

        loadDefaultPresets();
        setupControls();
        setupFocalLengthButtons();
        setupFilterButtons();
        refreshPresetListUI();
        setupImportExport();
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (isCameraPermissionGranted()) {
            startCamera();
            checkAndRequestOptionalPermissions();
            updateLocation();
        } else {
            requestPermissions();
        }
    }
    
    private void loadDefaultPresets() {
        ImageUtils.CurvePreset pDefault = new ImageUtils.CurvePreset();
        pDefault.name = "Reset";
        loadedPresets.add(pDefault);
    }

    private void setupImportExport() {
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) saveXmpToFile(uri);
                }
            }
        );

        importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) importXmpFromFile(uri);
                }
            }
        );
    }
    
    private void saveXmpToFile(Uri uri) {
        // Sync current UI state to preset object
        captureCurrentStateToPreset(currentPreset);
        
        String xmp = currentPreset.toXmp();
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(xmp.getBytes());
                os.close();
                Toast.makeText(this, "Exported: " + currentPreset.name, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Export Failed", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void importXmpFromFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            is.close();
            
            ImageUtils.CurvePreset newPreset = ImageUtils.CurvePreset.fromXmp(sb.toString());
            loadedPresets.add(newPreset);
            refreshPresetListUI();
            applyPreset(newPreset);
            Toast.makeText(this, "Imported: " + newPreset.name, Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Invalid XMP File", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupControls() {
        findViewById(R.id.btnCapture).setOnClickListener(v -> takePhoto());
        
        btnRatio.setOnClickListener(v -> {
            aspectRatioMode = (aspectRatioMode + 1) % 3;
            updateAspectRatioUI();
            startCamera(); 
        });
        
        findViewById(R.id.btnEditPreset).setOnClickListener(v -> enterEditorMode());
        findViewById(R.id.btnCloseEditor).setOnClickListener(v -> exitEditorMode());

        // Saturation Slider: 0 to 200 => -100 to 100
        sbSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSaturation = progress - 100;
                if (presetEditorContainer.getVisibility() == View.VISIBLE) {
                    updateFreezeFramePreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        findViewById(R.id.btnResetCurve).setOnClickListener(v -> {
            currentSaturation = 0;
            sbSaturation.setProgress(100);
            curveView.resetCurves();
            updateFreezeFramePreview();
        });
        
        findViewById(R.id.btnSavePreset).setOnClickListener(v -> showSavePresetDialog());
        
        findViewById(R.id.btnCurveRGB).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.RGB));
        findViewById(R.id.btnCurveR).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.RED));
        findViewById(R.id.btnCurveG).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.GREEN));
        findViewById(R.id.btnCurveB).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.BLUE));
        
        findViewById(R.id.btnWatermark).setOnClickListener(v -> showWatermarkSettingsDialog());
        
        findViewById(R.id.btnExportXmp).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/xml");
            intent.putExtra(Intent.EXTRA_TITLE, currentPreset.name + ".xmp");
            exportLauncher.launch(intent);
        });
        
        findViewById(R.id.btnImportXmp).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*"); 
            importLauncher.launch(intent);
        });
    }
    
    private void showSavePresetDialog() {
        EditText input = new EditText(this);
        input.setHint("Preset Name");
        input.setTextColor(Color.BLACK);
        
        new AlertDialog.Builder(this)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = input.getText().toString();
                if (!name.isEmpty()) {
                    ImageUtils.CurvePreset newPreset = new ImageUtils.CurvePreset();
                    captureCurrentStateToPreset(newPreset);
                    newPreset.name = name;
                    loadedPresets.add(newPreset);
                    refreshPresetListUI();
                    Toast.makeText(this, "Saved " + name, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void captureCurrentStateToPreset(ImageUtils.CurvePreset preset) {
        preset.rgb = curveView.getPoints(CurveView.Channel.RGB);
        preset.r = curveView.getPoints(CurveView.Channel.RED);
        preset.g = curveView.getPoints(CurveView.Channel.GREEN);
        preset.b = curveView.getPoints(CurveView.Channel.BLUE);
        preset.saturation = currentSaturation;
    }
    
    private void refreshPresetListUI() {
        llPresetList.removeAllViews();
        for (ImageUtils.CurvePreset preset : loadedPresets) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(preset.name);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(12);
            btn.setBackgroundResource(android.R.drawable.btn_default_small);
            btn.getBackground().setTint(Color.DKGRAY);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 16, 0);
            btn.setLayoutParams(params);
            
            btn.setOnClickListener(v -> applyPreset(preset));
            llPresetList.addView(btn);
        }
    }
    
    private void applyPreset(ImageUtils.CurvePreset preset) {
        currentPreset = preset;
        curveView.setPoints(CurveView.Channel.RGB, preset.rgb);
        curveView.setPoints(CurveView.Channel.RED, preset.r);
        curveView.setPoints(CurveView.Channel.GREEN, preset.g);
        curveView.setPoints(CurveView.Channel.BLUE, preset.b);
        
        currentSaturation = preset.saturation;
        sbSaturation.setProgress((int)(currentSaturation + 100));
        
        updateFreezeFramePreview();
    }
    
    private void enterEditorMode() {
        frozenPreviewBitmap = viewFinder.getBitmap();
        if (frozenPreviewBitmap != null) {
            ivEditPreview.setImageBitmap(frozenPreviewBitmap);
            ivEditPreview.setVisibility(View.VISIBLE);
            
            presetEditorContainer.setVisibility(View.VISIBLE);
            controlsContainer.setVisibility(View.GONE);
            
            updateFreezeFramePreview();
        } else {
            Toast.makeText(this, "Wait for preview...", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void exitEditorMode() {
        presetEditorContainer.setVisibility(View.GONE);
        ivEditPreview.setVisibility(View.GONE);
        controlsContainer.setVisibility(View.VISIBLE);
        frozenPreviewBitmap = null;
    }
    
    private void updateFreezeFramePreview() {
        if (frozenPreviewBitmap == null || presetEditorContainer.getVisibility() != View.VISIBLE) return;
        
        cameraExecutor.execute(() -> {
             int[] lutRGB = curveView.getLutRGB();
             int[] lutR = curveView.getLutR();
             int[] lutG = curveView.getLutG();
             int[] lutB = curveView.getLutB();
             
             Bitmap processed = ImageUtils.processImage(frozenPreviewBitmap, 
                 currentFilter, currentSaturation, 
                 lutRGB, lutR, lutG, lutB, 
                 new ImageUtils.WatermarkConfig() {{ enabled = false; }}, 
                 false); 
                 
             runOnUiThread(() -> ivEditPreview.setImageBitmap(processed));
        });
    }
    
    private void updateAspectRatioUI() {
        if (aspectRatioMode == 0) { 
            btnRatio.setText("4:3");
            maskTop.setVisibility(View.GONE);
            maskBottom.setVisibility(View.GONE);
        } else if (aspectRatioMode == 1) { 
            btnRatio.setText("16:9");
            maskTop.setVisibility(View.GONE);
            maskBottom.setVisibility(View.GONE);
        } else { 
            btnRatio.setText("1:1");
            maskTop.setVisibility(View.VISIBLE);
            maskBottom.setVisibility(View.VISIBLE);
        }
    }

    private void setupFilterButtons() {
        filterContainer.removeAllViews();
        for (ImageUtils.FilterType type : ImageUtils.FilterType.values()) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(type.name().replace("_", " "));
            btn.setTextColor(type == currentFilter ? Color.YELLOW : Color.WHITE);
            btn.setTextSize(11);
            btn.setBackgroundColor(Color.TRANSPARENT);
            
            btn.setOnClickListener(v -> {
                currentFilter = type;
                setupFilterButtons(); 
                if (presetEditorContainer.getVisibility() == View.VISIBLE) {
                    updateFreezeFramePreview();
                }
            });
            filterContainer.addView(btn);
        }
    }

    // ... (Focal Length, Camera Start, Permission, Watermark Dialog same as previous) ...
    // Re-implemented standard methods for completeness within XML block constraints

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
                int targetRatio = (aspectRatioMode == 1) ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;
                Preview preview = new Preview.Builder().setTargetAspectRatio(targetRatio).build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().setTargetAspectRatio(targetRatio).build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                calculateBaseFocalLength();
                applyFocalLengthZoom(selectedFocalLength);
            } catch (ExecutionException | InterruptedException e) {}
        }, ContextCompat.getMainExecutor(this));
    }

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
        } catch (Exception e) { baseEquivalentFocalLength = 24.0f; }
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        Toast.makeText(this, "Capturing...", Toast.LENGTH_SHORT).show();
        int[] lutRGB = curveView.getLutRGB();
        int[] lutR = curveView.getLutR();
        int[] lutG = curveView.getLutG();
        int[] lutB = curveView.getLutB();
        boolean crop = (aspectRatioMode == 2);
        float sat = currentSaturation;
        ImageUtils.FilterType filter = currentFilter;

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap = imageProxyToBitmap(image);
                image.close();
                if (bitmap == null) return;
                cameraExecutor.execute(() -> {
                    try {
                        Bitmap processed = ImageUtils.processImage(bitmap, filter, sat,
                                lutRGB, lutR, lutG, lutB, wmConfig, crop);
                        saveImage(processed);
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error Processing", Toast.LENGTH_SHORT).show());
                    }
                });
            }
            @Override public void onError(@NonNull ImageCaptureException exception) { Toast.makeText(MainActivity.this, "Capture Failed", Toast.LENGTH_SHORT).show(); }
        });
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getPlanes() == null || image.getPlanes().length == 0) return null;
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) return null;
        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void saveImage(Bitmap bitmap) {
        String filename = "CAM_" + System.currentTimeMillis() + ".jpg";
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camulator");
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        try {
            if (uri != null) {
                OutputStream stream = getContentResolver().openOutputStream(uri);
                if (stream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
                    stream.close();
                }
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    contentValues.clear();
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, contentValues, null, null);
                }
                MediaScannerConnection.scanFile(this, new String[]{ getPathFromUri(uri) }, null, null);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "image/jpeg");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Open Image"));
                });
            }
        } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Error Saving", Toast.LENGTH_SHORT).show()); }
    }
    
    private String getPathFromUri(Uri uri) {
        try {
             android.database.Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.Images.Media.DATA}, null, null, null);
             if (cursor != null) {
                 int idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                 cursor.moveToFirst();
                 String path = cursor.getString(idx);
                 cursor.close();
                 return path;
             }
        } catch (Exception e) {}
        return "";
    }
    
    private void updateLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    wmConfig.latLng = String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
                    cameraExecutor.execute(() -> {
                        Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                        try {
                            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                            if (addresses != null && !addresses.isEmpty()) {
                                Address addr = addresses.get(0);
                                String place = addr.getLocality();
                                if (place == null) place = addr.getSubAdminArea();
                                if (place == null) place = addr.getAdminArea();
                                String country = addr.getCountryName();
                                if (place != null && country != null) wmConfig.placeName = place + ", " + country;
                                else if (country != null) wmConfig.placeName = country;
                                else wmConfig.placeName = place;
                            }
                        } catch (IOException e) {}
                    });
                }
            });
        }
    }

    private boolean isCameraPermissionGranted() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 10);
    }
    
    private void checkAndRequestOptionalPermissions() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!missing.isEmpty()) ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), 11);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10) {
            if (isCameraPermissionGranted()) {
                startCamera();
                updateLocation();
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == 11) updateLocation();
    }
}