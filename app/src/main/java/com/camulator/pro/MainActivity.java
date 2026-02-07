package com.camulator.pro;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    
    private PreviewView viewFinder;
    private ImageView ivPreviewOverlay;
    private View vShutterFlash; 
    private CurveView curveView;
    private ConstraintLayout previewContainer, presetEditorContainer;
    private View controlsContainer;
    private LinearLayout focalLengthContainer, llPresetList;
    private Button btnRatio;
    private ImageView ivLastImage;

    // Tone & Grade Controls
    private SeekBar sbSaturation, sbHighlights, sbShadows, sbWhites, sbBlacks, sbMidtones;
    private SeekBar sbShadowHue, sbShadowSat, sbHighlightHue, sbHighlightSat;

    private FusedLocationProviderClient fusedLocationClient;
    private Camera camera;
    private Vibrator vibrator;

    private ImageUtils.WatermarkConfig wmConfig = new ImageUtils.WatermarkConfig();
    private List<ImageUtils.CurvePreset> loadedPresets = new ArrayList<>();
    private ImageUtils.CurvePreset currentPreset = new ImageUtils.CurvePreset();
    
    private int aspectRatioMode = 0; // 0=4:3, 1=16:9, 2=1:1
    
    private static final float FULL_FRAME_DIAGONAL = 43.2666f;
    private float baseEquivalentFocalLength = 24.0f; 
    private static final int[] FOCAL_LENGTHS = {16, 24, 28, 35, 50, 75, 85, 105, 135};
    private int selectedFocalLength = 24;
    
    private boolean isFrozen = false;
    private Bitmap frozenBitmap = null;
    
    // Memory Optimization: Reusable buffers
    private Bitmap renderBitmapA;
    private Bitmap renderBitmapB;
    private boolean useBufferA = true;
    private int[] cachedPixelBuffer; 
    
    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;
    
    // Cache for heavy calculations
    private int[][] cachedMasterLUTs;
    private float[] cachedColorMatrix;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            setContentView(R.layout.activity_main);

            // Bind Views
            viewFinder = findViewById(R.id.viewFinder);
            ivPreviewOverlay = findViewById(R.id.ivPreviewOverlay);
            previewContainer = findViewById(R.id.previewContainer);
            vShutterFlash = findViewById(R.id.vShutterFlash);
            curveView = findViewById(R.id.curveView);
            presetEditorContainer = findViewById(R.id.presetEditorContainer);
            controlsContainer = findViewById(R.id.controlsContainer);
            focalLengthContainer = findViewById(R.id.focalLengthContainer);
            llPresetList = findViewById(R.id.llPresetList);
            btnRatio = findViewById(R.id.btnRatio);
            ivLastImage = findViewById(R.id.ivLastImage);
            
            // Bind New Sliders
            sbSaturation = findViewById(R.id.sbSaturation);
            sbHighlights = findViewById(R.id.sbHighlights);
            sbShadows = findViewById(R.id.sbShadows);
            sbWhites = findViewById(R.id.sbWhites);
            sbBlacks = findViewById(R.id.sbBlacks);
            sbMidtones = findViewById(R.id.sbMidtones);
            sbShadowHue = findViewById(R.id.sbShadowHue);
            sbShadowSat = findViewById(R.id.sbShadowSat);
            sbHighlightHue = findViewById(R.id.sbHighlightHue);
            sbHighlightSat = findViewById(R.id.sbHighlightSat);
            
            try {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            } catch (Exception e) {}
            
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            if (curveView != null) {
                curveView.setOnCurveChangeListener(this::updatePresetFromUI);
            }

            if (viewFinder != null) {
                viewFinder.setVisibility(View.VISIBLE);
                viewFinder.setAlpha(0f);
            }

            loadDefaultPresets();
            setupControls();
            setupSliderListeners();
            setupFocalLengthButtons();
            refreshPresetListUI();
            setupImportExport();
            cameraExecutor = Executors.newSingleThreadExecutor();
            
            // Pre-calculate initial LUTs and Matrix
            updatePresetFromUI();

            if (ivPreviewOverlay != null) {
                ivPreviewOverlay.post(() -> {
                    try {
                        if (isCameraPermissionGranted()) {
                            startCamera();
                            checkAndRequestOptionalPermissions();
                            updateLocation();
                            loadLastImage();
                        } else {
                            requestPermissions();
                        }
                    } catch (Exception e) {}
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
    
    private void performHaptic() {
        try {
            if (vibrator != null && ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                } else {
                    vibrator.vibrate(20);
                }
            }
        } catch (Exception e) {}
    }

    private void setupSliderListeners() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updatePresetFromUI();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        sbSaturation.setOnSeekBarChangeListener(listener);
        sbHighlights.setOnSeekBarChangeListener(listener);
        sbShadows.setOnSeekBarChangeListener(listener);
        sbWhites.setOnSeekBarChangeListener(listener);
        sbBlacks.setOnSeekBarChangeListener(listener);
        sbMidtones.setOnSeekBarChangeListener(listener);
        sbShadowHue.setOnSeekBarChangeListener(listener);
        sbShadowSat.setOnSeekBarChangeListener(listener);
        sbHighlightHue.setOnSeekBarChangeListener(listener);
        sbHighlightSat.setOnSeekBarChangeListener(listener);
    }

    private void updatePresetFromUI() {
        if (curveView == null) return;
        currentPreset.rgb = curveView.getPoints(CurveView.Channel.RGB);
        currentPreset.r = curveView.getPoints(CurveView.Channel.RED);
        currentPreset.g = curveView.getPoints(CurveView.Channel.GREEN);
        currentPreset.b = curveView.getPoints(CurveView.Channel.BLUE);

        currentPreset.saturation = sbSaturation.getProgress() - 100;
        currentPreset.highlights = sbHighlights.getProgress() - 100;
        currentPreset.shadows = sbShadows.getProgress() - 100;
        currentPreset.whites = sbWhites.getProgress() - 100;
        currentPreset.black = sbBlacks.getProgress() - 100;
        currentPreset.midtones = sbMidtones.getProgress() - 100;
        
        currentPreset.shadowHue = sbShadowHue.getProgress();
        currentPreset.shadowSat = sbShadowSat.getProgress();
        currentPreset.highlightHue = sbHighlightHue.getProgress();
        currentPreset.highlightSat = sbHighlightSat.getProgress();
        
        // Regenerate Caches immediately
        cachedMasterLUTs = ImageUtils.generateMasterLUTs(currentPreset);
        
        if (currentPreset.saturation != 0) {
            ColorMatrix cm = new ColorMatrix();
            float satScale = 1.0f + (currentPreset.saturation / 100f);
            if (satScale < 0) satScale = 0;
            cm.setSaturation(satScale);
            cachedColorMatrix = cm.getArray();
        } else {
            cachedColorMatrix = null;
        }
        
        if (isFrozen) triggerPreviewUpdate();
    }
    
    private void updateUIFromPreset() {
        if (curveView != null) {
            curveView.setPoints(CurveView.Channel.RGB, currentPreset.rgb);
            curveView.setPoints(CurveView.Channel.RED, currentPreset.r);
            curveView.setPoints(CurveView.Channel.GREEN, currentPreset.g);
            curveView.setPoints(CurveView.Channel.BLUE, currentPreset.b);
        }
        sbSaturation.setProgress((int)currentPreset.saturation + 100);
        sbHighlights.setProgress((int)currentPreset.highlights + 100);
        sbShadows.setProgress((int)currentPreset.shadows + 100);
        sbWhites.setProgress((int)currentPreset.whites + 100);
        sbBlacks.setProgress((int)currentPreset.black + 100);
        sbMidtones.setProgress((int)currentPreset.midtones + 100);
        
        sbShadowHue.setProgress((int)currentPreset.shadowHue);
        sbShadowSat.setProgress((int)currentPreset.shadowSat);
        sbHighlightHue.setProgress((int)currentPreset.highlightHue);
        sbHighlightSat.setProgress((int)currentPreset.highlightSat);
        
        updatePresetFromUI();
    }

    private void startCamera() {
        if (isDestroyed() || isFinishing()) return;
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                if (cameraProvider == null) return;
                
                int targetRatio = (aspectRatioMode == 1) ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;

                // 1. Preview
                Preview preview = new Preview.Builder().setTargetAspectRatio(targetRatio).build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                
                Size targetResolution;
                if (aspectRatioMode == 1) {
                    targetResolution = new Size(720, 1280); 
                } else {
                    targetResolution = new Size(720, 960); 
                }

                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setTargetResolution(targetResolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888);
                
                imageAnalysis = analysisBuilder.build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                imageCapture = new ImageCapture.Builder()
                        .setTargetAspectRatio(targetRatio)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                
                try {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);
                    calculateBaseFocalLength();
                    applyFocalLengthZoom(selectedFocalLength);
                } catch (IllegalArgumentException e) {}

            } catch (ExecutionException | InterruptedException e) {
            } catch (Exception e) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(@NonNull ImageProxy image) {
        try {
            if (isFrozen) {
                image.close();
                return;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            
            if (width <= 0 || height <= 0) { image.close(); return; }
            
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            
            Bitmap targetBitmap = useBufferA ? renderBitmapA : renderBitmapB;
            if (targetBitmap == null || targetBitmap.getWidth() != width || targetBitmap.getHeight() != height) {
                targetBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                if (useBufferA) renderBitmapA = targetBitmap; else renderBitmapB = targetBitmap;
            }
            
            if (cachedPixelBuffer == null || cachedPixelBuffer.length != width * height) {
                cachedPixelBuffer = new int[width * height];
            }
            
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) { image.close(); return; }
            
            ByteBuffer buffer = planes[0].getBuffer();
            buffer.rewind();
            
            if (buffer.remaining() < width * height * 4) { image.close(); return; }
            
            targetBitmap.copyPixelsFromBuffer(buffer);
            image.close();

            int[][] luts = cachedMasterLUTs;
            float[] cm = cachedColorMatrix;
            
            if (luts != null) {
                ImageUtils.applyPreviewEffects(targetBitmap, cachedPixelBuffer, cm, 
                    luts[0], luts[1], luts[2], luts[3]);
            }

            final Bitmap finalBitmap = targetBitmap;
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing() && !isFrozen && finalBitmap != null) {
                    ivPreviewOverlay.setImageBitmap(finalBitmap);
                    if (ivPreviewOverlay.getRotation() != rotationDegrees) ivPreviewOverlay.setRotation(rotationDegrees);
                    // Use FIT_CENTER to ensure full image visibility within the constrained layout
                    ivPreviewOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
                }
            });
            useBufferA = !useBufferA;
            
        } catch (Exception e) { 
            try { image.close(); } catch(Exception ignored) {} 
        }
    }
    
    // Helper to query and load the latest image
    private void loadLastImage() {
        if (!isCameraPermissionGranted()) return;
        cameraExecutor.execute(() -> {
             String[] projection = new String[]{ MediaStore.Images.Media._ID };
             String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
             try (Cursor cursor = getContentResolver().query(
                     MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)) {
                 if (cursor != null && cursor.moveToFirst()) {
                     long id = cursor.getLong(0);
                     Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                     Bitmap thumb = null;
                     try {
                         thumb = getContentResolver().loadThumbnail(uri, new Size(200, 200), null);
                     } catch(IOException e) {}
                     
                     if (thumb != null) {
                         final Bitmap finalThumb = thumb;
                         runOnUiThread(() -> {
                             if (!isDestroyed()) ivLastImage.setImageBitmap(finalThumb);
                         });
                     }
                 }
             } catch(Exception e) {}
        });
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        performHaptic();
        
        if (vShutterFlash != null) {
            vShutterFlash.setVisibility(View.VISIBLE);
            vShutterFlash.setAlpha(1f);
            vShutterFlash.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { vShutterFlash.setVisibility(View.GONE); }
            }).start();
        }
        
        ImageUtils.CurvePreset presetUsed = new ImageUtils.CurvePreset();
        presetUsed.rgb = new ArrayList<>(currentPreset.rgb);
        presetUsed.r = new ArrayList<>(currentPreset.r);
        presetUsed.g = new ArrayList<>(currentPreset.g);
        presetUsed.b = new ArrayList<>(currentPreset.b);
        presetUsed.saturation = currentPreset.saturation;
        presetUsed.highlights = currentPreset.highlights;
        presetUsed.shadows = currentPreset.shadows;
        presetUsed.whites = currentPreset.whites;
        presetUsed.black = currentPreset.black;
        presetUsed.midtones = currentPreset.midtones;
        presetUsed.shadowHue = currentPreset.shadowHue;
        presetUsed.shadowSat = currentPreset.shadowSat;
        presetUsed.highlightHue = currentPreset.highlightHue;
        presetUsed.highlightSat = currentPreset.highlightSat;

        boolean crop = (aspectRatioMode == 2);
        ImageUtils.WatermarkConfig wm = wmConfig.clone(); 

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                cameraExecutor.execute(() -> {
                    try {
                        Bitmap bitmap = ImageUtils.imageProxyToBitmap(image);
                        image.close();
                        if (bitmap != null) {
                            Bitmap processed = ImageUtils.processImage(bitmap, presetUsed, wm, crop);
                            Uri savedUri = saveImage(processed);
                            if (savedUri != null) {
                                // Load the thumbnail for the newly saved URI
                                try {
                                    Bitmap thumb = getContentResolver().loadThumbnail(savedUri, new Size(200, 200), null);
                                    runOnUiThread(() -> {
                                        if (!isDestroyed()) {
                                            ivLastImage.setImageBitmap(thumb);
                                            Toast.makeText(getApplicationContext(), "Saved", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                } catch(Exception e) {}
                            }
                        }
                    } catch (Exception e) {}
                });
            }
            @Override public void onError(@NonNull ImageCaptureException exception) {}
        });
    }

    private void triggerPreviewUpdate() {
        if (isFrozen && frozenBitmap != null) {
            updateFreezeFrame();
        }
    }
    
    private void loadDefaultPresets() {
        ImageUtils.CurvePreset pDefault = new ImageUtils.CurvePreset();
        pDefault.name = "Reset";
        loadedPresets.add(pDefault);
    }

    private void setupImportExport() {
        try {
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
        } catch (Exception e) {}
    }
    
    private void saveXmpToFile(Uri uri) {
        String xmp = currentPreset.toXmp();
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) { os.write(xmp.getBytes()); os.close(); Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show(); }
        } catch (Exception e) {}
    }
    
    private void importXmpFromFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            is.close();
            ImageUtils.CurvePreset newPreset = ImageUtils.CurvePreset.fromXmp(sb.toString());
            loadedPresets.add(newPreset);
            refreshPresetListUI();
            currentPreset = newPreset;
            updateUIFromPreset();
            Toast.makeText(this, "Imported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {}
    }

    private void setupControls() {
        try {
            findViewById(R.id.btnCapture).setOnClickListener(v -> takePhoto());
            
            findViewById(R.id.btnGallery).setOnClickListener(v -> {
                performHaptic();
                startActivity(new Intent(this, GalleryActivity.class));
            });
            
            btnRatio.setOnClickListener(v -> {
                performHaptic();
                aspectRatioMode = (aspectRatioMode + 1) % 3;
                updateAspectRatioUI();
                startCamera(); 
            });
            
            findViewById(R.id.btnEditPreset).setOnClickListener(v -> { performHaptic(); enterEditorMode(); });
            findViewById(R.id.btnCloseEditor).setOnClickListener(v -> { performHaptic(); exitEditorMode(); });
            
            findViewById(R.id.btnResetCurve).setOnClickListener(v -> {
                performHaptic();
                curveView.resetCurves();
                updatePresetFromUI();
            });
            
            findViewById(R.id.btnSavePreset).setOnClickListener(v -> showSavePresetDialog());
            
            findViewById(R.id.btnCurveRGB).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.RGB));
            findViewById(R.id.btnCurveR).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.RED));
            findViewById(R.id.btnCurveG).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.GREEN));
            findViewById(R.id.btnCurveB).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.BLUE));
            
            findViewById(R.id.btnWatermark).setOnClickListener(v -> { performHaptic(); showWatermarkSettingsDialog(); });
            
            findViewById(R.id.btnExportXmp).setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/xml");
                    intent.putExtra(Intent.EXTRA_TITLE, currentPreset.name + ".xmp");
                    exportLauncher.launch(intent);
                } catch(Exception e) {}
            });
            
            findViewById(R.id.btnImportXmp).setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*"); 
                    importLauncher.launch(intent);
                } catch(Exception e) {}
            });
        } catch (Exception e) {}
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
                    // Copy data
                    newPreset.rgb = new ArrayList<>(currentPreset.rgb);
                    newPreset.r = new ArrayList<>(currentPreset.r);
                    newPreset.g = new ArrayList<>(currentPreset.g);
                    newPreset.b = new ArrayList<>(currentPreset.b);
                    newPreset.saturation = currentPreset.saturation;
                    newPreset.highlights = currentPreset.highlights;
                    newPreset.shadows = currentPreset.shadows;
                    newPreset.whites = currentPreset.whites;
                    newPreset.black = currentPreset.black;
                    newPreset.midtones = currentPreset.midtones;
                    newPreset.shadowHue = currentPreset.shadowHue;
                    newPreset.shadowSat = currentPreset.shadowSat;
                    newPreset.highlightHue = currentPreset.highlightHue;
                    newPreset.highlightSat = currentPreset.highlightSat;
                    
                    newPreset.name = name;
                    loadedPresets.add(newPreset);
                    refreshPresetListUI();
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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
            
            btn.setOnClickListener(v -> {
                performHaptic();
                currentPreset = preset;
                updateUIFromPreset();
            });
            llPresetList.addView(btn);
        }
    }
    
    private void enterEditorMode() {
        Bitmap currentDisplay = useBufferA ? renderBitmapB : renderBitmapA;
        if (currentDisplay != null) {
            isFrozen = true;
            frozenBitmap = currentDisplay.copy(Bitmap.Config.ARGB_8888, true);
            if (presetEditorContainer != null) presetEditorContainer.setVisibility(View.VISIBLE);
            if (controlsContainer != null) controlsContainer.setVisibility(View.GONE);
            
            // Resize preview container to show full image in the top space
            if (previewContainer != null && presetEditorContainer != null) {
                 ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) previewContainer.getLayoutParams();
                 params.bottomToTop = presetEditorContainer.getId();
                 params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                 previewContainer.setLayoutParams(params);
                 
                 // Ensure the overlay scales to fit inside the new smaller container fully
                 ivPreviewOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            
            updateFreezeFrame();
        } else {
            Toast.makeText(this, "Wait for stream...", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void exitEditorMode() {
        if (presetEditorContainer != null) presetEditorContainer.setVisibility(View.GONE);
        if (controlsContainer != null) controlsContainer.setVisibility(View.VISIBLE);
        
        // Reset preview container to full screen
        if (previewContainer != null) {
             ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) previewContainer.getLayoutParams();
             params.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
             params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
             previewContainer.setLayoutParams(params);
             
             // Reset scale type logic
             ivPreviewOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        frozenBitmap = null;
        isFrozen = false; 
    }
    
    private void updateFreezeFrame() {
        if (frozenBitmap == null) return;
        cameraExecutor.execute(() -> {
             Bitmap temp = frozenBitmap.copy(Bitmap.Config.ARGB_8888, true);
             int[][] luts = cachedMasterLUTs;
             float[] cm = cachedColorMatrix;
             if (luts != null) {
                 ImageUtils.applyPreviewEffects(temp, null, cm, luts[0], luts[1], luts[2], luts[3]);
             }
             runOnUiThread(() -> {
                 if (!isDestroyed()) ivPreviewOverlay.setImageBitmap(temp);
             });
        });
    }
    
    private void updateAspectRatioUI() {
        if (btnRatio == null || previewContainer == null) return;
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) previewContainer.getLayoutParams();
        
        if (aspectRatioMode == 0) { 
            btnRatio.setText("4:3");
            params.dimensionRatio = "3:4";
        } else if (aspectRatioMode == 1) { 
            btnRatio.setText("16:9");
            params.dimensionRatio = "9:16";
        } else { 
            btnRatio.setText("1:1");
            params.dimensionRatio = "1:1";
        }
        previewContainer.setLayoutParams(params);
    }

    private void showWatermarkSettingsDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.dialog_watermark_settings);

        Switch swEnabled = dialog.findViewById(R.id.swWatermarkEnabled);
        Switch swLogo = dialog.findViewById(R.id.swShowLogo);
        EditText etText = dialog.findViewById(R.id.etCustomText);
        Switch swTime = dialog.findViewById(R.id.swShowTime);
        Switch swCoords = dialog.findViewById(R.id.swShowCoords);
        
        // New Switches
        Switch swDistrict = dialog.findViewById(R.id.swShowDistrict);
        Switch swStreet = dialog.findViewById(R.id.swShowStreet);

        RadioGroup rgPos = dialog.findViewById(R.id.rgPosition);
        RadioGroup rgStyle = dialog.findViewById(R.id.rgStyle);
        RadioGroup rgBg = dialog.findViewById(R.id.rgBgColor);
        SeekBar sbSize = dialog.findViewById(R.id.sbWatermarkSize);
        TextView tvSize = dialog.findViewById(R.id.tvSizeValue);

        if (swEnabled != null) {
            swEnabled.setChecked(wmConfig.enabled);
            swLogo.setChecked(wmConfig.showLogo);
            etText.setText(wmConfig.customText);
            swTime.setChecked(wmConfig.showTime);
            swCoords.setChecked(wmConfig.showCoords);
            
            // Set State
            swDistrict.setChecked(wmConfig.showDistrict);
            swStreet.setChecked(wmConfig.showStreet);
            
            int progress = (int) (wmConfig.watermarkScale * 1000);
            sbSize.setProgress(progress);
            tvSize.setText(String.format(Locale.US, "%.1f%%", wmConfig.watermarkScale * 100));

            sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                     float val = progress / 1000f; 
                     tvSize.setText(String.format(Locale.US, "%.1f%%", val * 100));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            switch (wmConfig.position) {
                case 0: rgPos.check(R.id.rbPosLeft); break;
                case 1: rgPos.check(R.id.rbPosCenter); break;
                case 2: rgPos.check(R.id.rbPosRight); break;
            }
            
            if (wmConfig.styleFooter) rgStyle.check(R.id.rbStyleFooter);
            else rgStyle.check(R.id.rbStyleOverlay);
            
            if (wmConfig.backgroundColor == Color.BLACK) rgBg.check(R.id.rbBgBlack);
            else rgBg.check(R.id.rbBgWhite);

            dialog.setOnDismissListener(d -> {
                wmConfig.enabled = swEnabled.isChecked();
                wmConfig.showLogo = swLogo.isChecked();
                wmConfig.customText = etText.getText().toString();
                wmConfig.showTime = swTime.isChecked();
                wmConfig.showCoords = swCoords.isChecked();
                wmConfig.showDistrict = swDistrict.isChecked();
                wmConfig.showStreet = swStreet.isChecked();
                wmConfig.watermarkScale = sbSize.getProgress() / 1000f;

                int posId = rgPos.getCheckedRadioButtonId();
                if (posId == R.id.rbPosLeft) wmConfig.position = 0;
                else if (posId == R.id.rbPosCenter) wmConfig.position = 1;
                else if (posId == R.id.rbPosRight) wmConfig.position = 2;
                
                wmConfig.styleFooter = (rgStyle.getCheckedRadioButtonId() == R.id.rbStyleFooter);
                
                if (rgBg.getCheckedRadioButtonId() == R.id.rbBgBlack) {
                    wmConfig.backgroundColor = Color.BLACK;
                    wmConfig.textColor = Color.WHITE;
                } else {
                    wmConfig.backgroundColor = Color.WHITE;
                    wmConfig.textColor = Color.BLACK;
                }
                
                // Trigger update to refresh string with new granularity
                updateLocation();
                
                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
            });
        }
        dialog.show();
    }

    private void setupFocalLengthButtons() {
        if (focalLengthContainer == null) return;
        focalLengthContainer.removeAllViews();
        for (int focalLength : FOCAL_LENGTHS) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(focalLength + "mm");
            btn.setTextColor(focalLength == selectedFocalLength ? Color.YELLOW : Color.WHITE);
            btn.setTextSize(13);
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setOnClickListener(v -> {
                performHaptic();
                selectedFocalLength = focalLength;
                applyFocalLengthZoom(focalLength);
                updateFocalLengthUI();
            });
            focalLengthContainer.addView(btn);
        }
    }

    private void updateFocalLengthUI() {
        if (focalLengthContainer == null) return;
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

    private void calculateBaseFocalLength() {
        try {
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            android.util.SizeF sensorSize = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focalLengths = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (sensorSize != null && focalLengths != null && focalLengths.length > 0) {
                float w = sensorSize.getWidth();
                float h = sensorSize.getHeight();
                float sensorDiagonal = (float) Math.sqrt(w * w + h * h);
                float cropFactor = FULL_FRAME_DIAGONAL / sensorDiagonal;
                baseEquivalentFocalLength = focalLengths[0] * cropFactor;
            }
        } catch (Exception e) { baseEquivalentFocalLength = 24.0f; }
    }
    
    private Uri saveImage(Bitmap bitmap) {
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream);
                    stream.close();
                }
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    contentValues.clear();
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, contentValues, null, null);
                }
                return uri;
            }
        } catch (Exception e) {}
        return null;
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
                                wmConfig.placeName = getFormattedPlace(addr, wmConfig);
                            }
                        } catch (IOException e) {}
                    });
                }
            });
        }
    }
    
    private String getFormattedPlace(Address addr, ImageUtils.WatermarkConfig config) {
        StringBuilder sb = new StringBuilder();
        
        // Show City/District Level
        if (config.showDistrict) {
            if (addr.getLocality() != null) {
                sb.append(addr.getLocality());
            }
            if (addr.getSubLocality() != null) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(addr.getSubLocality());
            } else if (addr.getSubAdminArea() != null) {
                // If subLocality is null (common in some regions), try subAdminArea (County/District)
                if (sb.length() > 0) sb.append(" ");
                sb.append(addr.getSubAdminArea());
            }
        }
        
        // Show Street Level
        if (config.showStreet) {
            if (addr.getThoroughfare() != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(addr.getThoroughfare());
            }
        }
        
        // Fallback: If nothing is selected but country is available, showing something is better than empty
        if (sb.length() == 0 && addr.getCountryName() != null) {
            return addr.getCountryName();
        }
        
        return sb.toString();
    }

    private boolean isCameraPermissionGranted() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
             permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 10);
    }
    
    private void checkAndRequestOptionalPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
             ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 11);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10) {
            if (isCameraPermissionGranted()) {
                startCamera();
                updateLocation();
                loadLastImage();
            }
        }
    }
}