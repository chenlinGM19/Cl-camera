package com.camulator.pro;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
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
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void loadImages() {
        executor.execute(() -> {
            List<Uri> uris = new ArrayList<>();
            String[] projection = new String[]{ MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN };
            
            String selection = null;
            String[] selectionArgs = null;
            String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";

            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder)) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
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
        private final LruCache<Uri, Bitmap> memoryCache;

        public GalleryAdapter(Context context) {
            this.context = context;
            final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
            final int cacheSize = maxMemory / 8; 
            memoryCache = new LruCache<Uri, Bitmap>(cacheSize) {
                @Override
                protected int sizeOf(Uri key, Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };
        }

        public void setUris(List<Uri> newUris) {
            uris.clear();
            uris.addAll(newUris);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery_image, parent, false);
            int width = parent.getWidth() / 3;
            view.getLayoutParams().height = width;
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Uri uri = uris.get(position);
            holder.ivThumb.setImageBitmap(null); 
            
            Bitmap cached = memoryCache.get(uri);
            if (cached != null) {
                holder.ivThumb.setImageBitmap(cached);
            } else {
                executor.execute(() -> {
                    try {
                        Bitmap thumb = null;
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            try {
                                thumb = context.getContentResolver().loadThumbnail(uri, new Size(256, 256), null);
                            } catch (IOException e) {}
                        } 
                        
                        if (thumb == null) {
                            long id = ContentUris.parseId(uri);
                            thumb = MediaStore.Images.Thumbnails.getThumbnail(context.getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                        }
                        
                        if (thumb != null) {
                            memoryCache.put(uri, thumb);
                            final Bitmap finalThumb = thumb;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (holder.getBindingAdapterPosition() == position) {
                                    holder.ivThumb.setImageBitmap(finalThumb);
                                }
                            });
                        }
                    } catch (Exception e) {}
                });
            }

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(context, ImageViewerActivity.class);
                intent.setData(uri);
                context.startActivity(intent);
            });
        }

        @Override public int getItemCount() { return uris.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivThumb;
            ViewHolder(View itemView) { super(itemView); ivThumb = itemView.findViewById(R.id.ivThumb); }
        }
    }
}