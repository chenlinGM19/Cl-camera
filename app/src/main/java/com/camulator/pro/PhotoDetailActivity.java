package com.camulator.pro;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PhotoDetailActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private List<File> images = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_detail);

        viewPager = findViewById(R.id.viewPager);
        ImageView btnBack = findViewById(R.id.btnBack);
        
        btnBack.setOnClickListener(v -> finish());

        String startPath = getIntent().getStringExtra("current_path");
        String folderPath = getIntent().getStringExtra("folder_path");

        if (folderPath != null) {
            loadImages(folderPath);
            int startIndex = -1;
            if (startPath != null) {
                for (int i = 0; i < images.size(); i++) {
                    if (images.get(i).getAbsolutePath().equals(startPath)) {
                        startIndex = i;
                        break;
                    }
                }
            }
            
            PhotoPagerAdapter adapter = new PhotoPagerAdapter(images);
            viewPager.setAdapter(adapter);
            if (startIndex >= 0) {
                viewPager.setCurrentItem(startIndex, false);
            }
        }
    }

    private void loadImages(String folderPath) {
        File dir = new File(folderPath);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg"));
            if (files != null) {
                images.addAll(Arrays.asList(files));
                // Sort by date descending
                Collections.sort(images, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            }
        }
    }

    private class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder> {
        private final List<File> fileList;

        PhotoPagerAdapter(List<File> fileList) {
            this.fileList = fileList;
        }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView imageView = new ImageView(parent.getContext());
            imageView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); // Shows full image
            imageView.setBackgroundColor(0xFF000000);
            return new PhotoViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            Glide.with(holder.itemView)
                    .load(fileList.get(position))
                    .into((ImageView) holder.itemView);
        }

        @Override
        public int getItemCount() {
            return fileList.size();
        }

        class PhotoViewHolder extends RecyclerView.ViewHolder {
            PhotoViewHolder(View itemView) {
                super(itemView);
            }
        }
    }
}