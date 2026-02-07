package com.camulator.pro;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageViewerActivity extends AppCompatActivity {

    private Uri imageUri;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        ImageView ivFull = findViewById(R.id.ivFull);
        imageUri = getIntent().getData();

        if (imageUri != null) {
            // Load large image in background
            executor.execute(() -> {
                try {
                    InputStream is = getContentResolver().openInputStream(imageUri);
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (is != null) is.close();
                    
                    new Handler(Looper.getMainLooper()).post(() -> {
                         if (!isDestroyed() && !isFinishing()) {
                             ivFull.setImageBitmap(bmp);
                         }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        
        findViewById(R.id.btnShare).setOnClickListener(v -> {
            if (imageUri != null) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("image/jpeg");
                shareIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
                startActivity(Intent.createChooser(shareIntent, "Share Photo"));
            }
        });

        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Photo?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteImage())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteImage() {
        if (imageUri != null) {
            try {
                getContentResolver().delete(imageUri, null, null);
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to delete: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}