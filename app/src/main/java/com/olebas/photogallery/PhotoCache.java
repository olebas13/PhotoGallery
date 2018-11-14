package com.olebas.photogallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;

public class PhotoCache {

    private static PhotoCache sPhotoCache;
    private LruCache<String, Bitmap> mCachedPhotos;

    public PhotoCache(Context context) {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;

        mCachedPhotos = new LruCache<>(cacheSize);

        mCachedPhotos = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public static PhotoCache get(Context context) {
        if (sPhotoCache == null) {
            sPhotoCache = new PhotoCache(context);
        }
        return sPhotoCache;
    }

    public void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (key == null || bitmap == null) {
            return;
        }

        if (getBitmapFromMemCache(key) == null) {
            mCachedPhotos.put(key, bitmap);
        }
    }

    public Bitmap getBitmapFromMemCache(String key) {
        if (key == null) {
            return null;
        }
        return mCachedPhotos.get(key);
    }
}
