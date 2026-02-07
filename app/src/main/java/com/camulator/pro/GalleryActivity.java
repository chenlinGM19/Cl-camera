package com.camulator.pro;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends AppCompatActivity {

    private RecyclerView rvGallery;
    private TextView tvEmpty;
    private GalleryAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        rvGallery = findViewById(R.id.rvGallery);
        tvEmpty = findViewById(R.id.tvEmpty);
        
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        rvGallery.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new GalleryAdapter(this);
        rvGallery.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadImages();
    }

    private void loadImages() {
        executor.execute(() -> {
            List<Uri> uris = new ArrayList<>();
            
            String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN
            };
            
            // Query logic for API 29+ (scoped storage) vs older
            // We use RELATIVE_PATH to filter only our app's images
            String selection = null;
            String[] selectionArgs = null;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                selectionArgs = new String[]{"%Pictures/Camulator%"};
            } else {
                // Fallback for older APIs if simpler path check needed
                selection = MediaStore.Images.Media.DATA + " LIKE ?";
                selectionArgs = new String[]{"%Camulator%"};
            }

            String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";

            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder)) {

                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        uris.add(contentUri);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            runOnUiThread(() -> {
                if (uris.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvGallery.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvGallery.setVisibility(View.VISIBLE);
                    adapter.setUris(uris);
                }
            });
        });
    }

    private class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
        private final List<Uri> uris = new ArrayList<>();
        private final Context context;

        public GalleryAdapter(Context context) {
            this.context = context;
        }

        public void setUris(List<Uri> newUris) {
            uris.clear();
            uris.addAll(newUris);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery_image, parent, false);
            // Fix height for grid items dynamically
            int width = parent.getWidth() / 3;
            view.getLayoutParams().height = width;
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Uri uri = uris.get(position);
            
            // Native Thumbnail loading
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    Bitmap thumb = context.getContentResolver().loadThumbnail(uri, new Size(300, 300), null);
                    holder.ivThumb.setImageBitmap(thumb);
                } else {
                    // Basic fallback
                    holder.ivThumb.setImageURI(uri); 
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(context, ImageViewerActivity.class);
                intent.setData(uri);
                context.startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivThumb;
            ViewHolder(View itemView) {
                super(itemView);
                ivThumb = itemView.findViewById(R.id.ivThumb);
            }
        }
    }
}