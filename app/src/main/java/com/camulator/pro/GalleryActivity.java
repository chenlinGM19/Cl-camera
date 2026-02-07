package com.camulator.pro;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends AppCompatActivity {

    private GalleryAdapter adapter;
    private List<File> fileList = new ArrayList<>();
    private ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        
        // Toolbar
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        
        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvGallery);
        
        // 3 Columns Grid
        int spanCount = 3;
        rv.setLayoutManager(new GridLayoutManager(this, spanCount));
        rv.addItemDecoration(new GridSpacingItemDecoration(spanCount, 8, true));
        
        adapter = new GalleryAdapter(fileList);
        rv.setAdapter(adapter);
        
        loadImages();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadExecutor.shutdown();
    }
    
    private void loadImages() {
        loadExecutor.execute(() -> {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            List<File> files = new ArrayList<>();
            if (dir != null && dir.listFiles() != null) {
                File[] fArray = dir.listFiles((d, name) -> name.endsWith(".jpg") || name.endsWith(".jpeg"));
                if (fArray != null) {
                    files.addAll(Arrays.asList(fArray));
                    // Sort new to old
                    Collections.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                }
            }
            
            new Handler(Looper.getMainLooper()).post(() -> {
                fileList.clear();
                fileList.addAll(files);
                adapter.notifyDataSetChanged();
                updateEmptyState();
            });
        });
    }
    
    private void updateEmptyState() {
        tvEmpty.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
    }
    
    private void openImage(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "image/*");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open image", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void confirmDelete(int position) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Photo")
            .setMessage("Are you sure you want to delete this photo?")
            .setPositiveButton("Delete", (dialog, which) -> deleteImage(position))
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void deleteImage(int position) {
        File file = fileList.get(position);
        if (file.delete()) {
            fileList.remove(position);
            adapter.notifyItemRemoved(position);
            updateEmptyState();
            Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show();
        }
    }
    
    // --- Adapter ---
    
    private class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
        private final List<File> images;
        
        public GalleryAdapter(List<File> images) {
            this.images = images;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            SquareImageView iv = new SquareImageView(parent.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF222222); 
            return new ViewHolder(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File file = images.get(position);
            
            Glide.with(holder.itemView)
                 .load(file)
                 .diskCacheStrategy(DiskCacheStrategy.ALL)
                 .centerCrop()
                 .into((ImageView) holder.itemView);
                 
            holder.itemView.setOnClickListener(v -> openImage(file));
            holder.itemView.setOnLongClickListener(v -> {
                confirmDelete(holder.getAdapterPosition());
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return images.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
    
    // Simple helper view for square grid items
    private class SquareImageView extends androidx.appcompat.widget.AppCompatImageView {
        public SquareImageView(android.content.Context context) {
            super(context);
        }
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec); // Height = Width
        }
    }
    
    // --- Item Decoration ---
    
    public static class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
        private int spanCount;
        private int spacing; // px
        private boolean includeEdge;

        public GridSpacingItemDecoration(int spanCount, int spacingDp, boolean includeEdge) {
            this.spanCount = spanCount;
            // quick px conversion
            this.spacing = (int) (spacingDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
            this.includeEdge = includeEdge;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view); 
            int column = position % spanCount; 

            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount;
                outRect.right = (column + 1) * spacing / spanCount;

                if (position < spanCount) { 
                    outRect.top = spacing;
                }
                outRect.bottom = spacing; 
            } else {
                outRect.left = column * spacing / spanCount; 
                outRect.right = spacing - (column + 1) * spacing / spanCount;
                if (position >= spanCount) {
                    outRect.top = spacing; 
                }
            }
        }
    }
}