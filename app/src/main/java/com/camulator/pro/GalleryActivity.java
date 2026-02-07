package com.camulator.pro;

import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GalleryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        
        RecyclerView rv = findViewById(R.id.rvGallery);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        List<File> files = new ArrayList<>();
        if (dir != null && dir.listFiles() != null) {
            files.addAll(Arrays.asList(dir.listFiles()));
            // Sort new to old
            Collections.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        }
        
        rv.setAdapter(new GalleryAdapter(files));
    }
    
    private class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
        private final List<File> images;
        
        public GalleryAdapter(List<File> images) {
            this.images = images;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new ViewHolder(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Glide.with(holder.itemView).load(images.get(position)).into((ImageView) holder.itemView);
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
}