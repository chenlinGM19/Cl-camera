package com.camulator.pro;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
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
        
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        
        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvGallery);
        
        int spanCount = 3;
        rv.setLayoutManager(new GridLayoutManager(this, spanCount));
        rv.addItemDecoration(new GridSpacingItemDecoration(spanCount, 4, true));
        
        adapter = new GalleryAdapter(fileList);
        rv.setAdapter(adapter);
        
        loadImages();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadExecutor.shutdown();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        loadImages(); // Reload if deletion happened
    }
    
    private void loadImages() {
        loadExecutor.execute(() -> {
            // Load from Public directory first
            File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CamulatorPro");
            List<File> files = new ArrayList<>();
            
            if (publicDir.exists() && publicDir.listFiles() != null) {
                File[] fArray = publicDir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg"));
                if (fArray != null) files.addAll(Arrays.asList(fArray));
            }
            
            // Also check internal cache/files if we had any legacy files
            File privateDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (privateDir != null && privateDir.listFiles() != null) {
                 File[] fArray = privateDir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
                 if (fArray != null) files.addAll(Arrays.asList(fArray));
            }

            if (!files.isEmpty()) {
                Collections.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
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
    
    private void openImage(int position) {
        // Pass the folder path and the current file name to avoid passing huge list
        if (position < 0 || position >= fileList.size()) return;
        
        File target = fileList.get(position);
        Intent intent = new Intent(this, PhotoDetailActivity.class);
        intent.putExtra("current_path", target.getAbsolutePath());
        intent.putExtra("folder_path", target.getParent()); 
        startActivity(intent);
    }
    
    private void confirmDelete(int position) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Photo")
            .setMessage("Are you sure?")
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
        } else {
            Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show();
        }
    }
    
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
                 
            holder.itemView.setOnClickListener(v -> openImage(holder.getAdapterPosition()));
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
    
    private class SquareImageView extends androidx.appcompat.widget.AppCompatImageView {
        public SquareImageView(android.content.Context context) { super(context); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
    }
    
    public static class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
        private int spanCount;
        private int spacing; 
        private boolean includeEdge;
        public GridSpacingItemDecoration(int spanCount, int spacingDp, boolean includeEdge) {
            this.spanCount = spanCount;
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
                if (position < spanCount) outRect.top = spacing;
                outRect.bottom = spacing; 
            } else {
                outRect.left = column * spacing / spanCount; 
                outRect.right = spacing - (column + 1) * spacing / spanCount;
                if (position >= spanCount) outRect.top = spacing; 
            }
        }
    }
}