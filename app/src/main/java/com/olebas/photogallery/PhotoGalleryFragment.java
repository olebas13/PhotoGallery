package com.olebas.photogallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.ProgressBar;

import java.util.ArrayList;
import java.util.List;


public class PhotoGalleryFragment extends Fragment {

    private static final String TAG = "PhotoGalleryFragment";

    private RecyclerView mPhotoRecyclerView;
    private ProgressBar mProgressBar;
    private List<GalleryItem> mItems = new ArrayList<>();
    private ThumbnailDownloader<PhotoHolder> mThumbnailDownloader;
    private ThumbnailPreloader<Integer> mThumbnailPreloader;
    private int mPageNumber = 1;
    private int mNumColumns = 3;
    private boolean mNewQuery = true;

    private LruCache<String, Bitmap> mImageCache;

    public static PhotoGalleryFragment newInstance() {
        return new PhotoGalleryFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        setHasOptionsMenu(true);
        updateItems();

        Handler responseHandler = new Handler();
        mThumbnailDownloader = new ThumbnailDownloader<>(responseHandler);
        mThumbnailPreloader = new ThumbnailPreloader<Integer>(responseHandler);

        mThumbnailDownloader.setThumbnailDownloadListener(new ThumbnailDownloader.ThumbnailDownloadListener<PhotoHolder>() {
            @Override
            public void onThumbnailDownloaded(PhotoHolder photoHolder, Bitmap bitmap, String url) {
                Drawable drawable = new BitmapDrawable(getResources(), bitmap);
                PhotoCache photoCache = PhotoCache.get(getContext());
                photoCache.addBitmapToMemoryCache(url, bitmap);
                photoHolder.bindDrawable(drawable);
            }
        });

        mThumbnailPreloader.setThumbnailDownloadListener(new ThumbnailPreloader.ThumbnailDownloadListener<Integer>() {
            @Override
            public void onThumbnailDownloaded(Integer target, Bitmap thumbnail, String url) {
                PhotoCache photoCache = PhotoCache.get(getContext());
                photoCache.addBitmapToMemoryCache(url, thumbnail);
            }
        });
        mThumbnailDownloader.start();
        mThumbnailDownloader.getLooper();

        Log.i(TAG, "Download background thread started");

        mThumbnailPreloader.setPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE);
        mThumbnailPreloader.start();
        mThumbnailPreloader.getLooper();
        Log.i(TAG, "Preloader background thread started");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photo_gallery, container, false);
        mProgressBar = (ProgressBar) view.findViewById(R.id.circle_loader);
        mPhotoRecyclerView = (RecyclerView) view.findViewById(R.id.photo_recycler_view);
        mPhotoRecyclerView.setLayoutManager(new GridLayoutManager(getActivity(), mNumColumns));
        mPhotoRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                int totalItems = lm.getItemCount();
                int lastVisibleItem = lm.findLastVisibleItemPosition();
                preloadPhotos();

                if ((lastVisibleItem + 10) >= totalItems && mPageNumber < 10) {
                    mPageNumber++;
                    updateItems();
                }
            }
        });

        mPhotoRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                GridLayoutManager manager = (GridLayoutManager) mPhotoRecyclerView.getLayoutManager();
                float currentWidth = manager.getWidth();
                mNumColumns = (int) currentWidth / 300;
                manager.setSpanCount(mNumColumns);
            }
        });
        setupAdapter();
        return view;
    }

    private void preloadPhotos() {
        GridLayoutManager lm = (GridLayoutManager) mPhotoRecyclerView.getLayoutManager();
        int firstVisiblePosition = lm.findFirstVisibleItemPosition();
        int lastVisiblePosition = lm.findLastVisibleItemPosition();
        PhotoCache photoCache = PhotoCache.get(getContext());
        if (firstVisiblePosition - 10 >= 0) {
            // load previous 10 images into cache
            for (int position = firstVisiblePosition - 10; position < firstVisiblePosition; position++) {
                String url = mItems.get(position).getUrl();
                if (photoCache.getBitmapFromMemCache(url) != null) {
                    // No need to re-download this
                    continue;
                }
                mThumbnailPreloader.queueThumbnail(position, url);
            }
            // load next 10 images
            if (lastVisiblePosition + 10 <= mItems.size()) {
                for (int position = lastVisiblePosition + 1; position < lastVisiblePosition + 10; position++) {
                    String url = mItems.get(position).getUrl();
                    if (photoCache.getBitmapFromMemCache(url) != null) {
                        // no need to re-download this
                        Log.i(TAG, "Already cached");
                        continue;
                    }
                    mThumbnailPreloader.queueThumbnail(position, url);
                }
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.fragment_photo_gallery, menu);

        MenuItem searchItem = menu.findItem(R.id.menu_item_search);
        final SearchView searchView = (SearchView) searchItem.getActionView();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                Log.d(TAG, "QueryTextSubmit: " + query);
                QueryPreferences.setStoredQuery(getActivity(), query);
                hideKeyboard();
                mProgressBar.setVisibility(View.VISIBLE);
                mNewQuery = true;
                mItems = new ArrayList<GalleryItem>();
                setupAdapter();
                updateItems();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                Log.d(TAG, "QueryTextChange: " + query);
                return false;
            }
        });

        searchView.setOnSearchClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String query = QueryPreferences.getStoredQuery(getActivity());
                searchView.setQuery(query, false);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        View currentFocus = (View) getActivity().getCurrentFocus();
        IBinder windowToken = currentFocus == null ? null : currentFocus.getWindowToken();

        inputManager.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_clear:
                QueryPreferences.setStoredQuery(getActivity(), null);
                updateItems();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateItems() {
        String query = QueryPreferences.getStoredQuery(getActivity());
        new FetchItemTask(query).execute();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mThumbnailDownloader.quit();
        Log.i(TAG, "Background thread destroyed");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mThumbnailDownloader.clearQuene();
    }

    private void setupAdapter() {
        if (isAdded()) {
            mPhotoRecyclerView.setAdapter(new PhotoAdapter(mItems));
        }
        preloadPhotos();
    }

    private class FetchItemTask extends AsyncTask<Void, Void, List<GalleryItem>> {

        private String mQuery;

        public FetchItemTask(String query) {
            mQuery = query;
        }

        @Override
        protected List<GalleryItem> doInBackground(Void... params) {

            if (mQuery == null) {
                return new FlickrFetchr().fetchRecentPhotos();
            } else {
                return new FlickrFetchr().searchPhotos(mQuery);
            }
        }

        @Override
        protected void onPostExecute(List<GalleryItem> galleryItems) {
            if (mProgressBar.getVisibility() == View.VISIBLE) {
                mProgressBar.setVisibility(View.GONE);
            }

            if (mItems.size() == 0 || mNewQuery) {
                mItems = galleryItems;
                mNewQuery = false;
                setupAdapter();
            } else {
                int oldSize = mItems.size();
                mItems.addAll(galleryItems);
                mPhotoRecyclerView.getAdapter().notifyItemRangeInserted(oldSize, galleryItems.size());
            }
        }
    }


    private class PhotoHolder extends RecyclerView.ViewHolder {

        private ImageView mItemImageView;

        public PhotoHolder(@NonNull View itemView) {
            super(itemView);
            mItemImageView = (ImageView) itemView.findViewById(R.id.item_image_view);
        }

        public void bindDrawable(Drawable drawable) {
            mItemImageView.setImageDrawable(drawable);
        }
    }


    private class PhotoAdapter extends RecyclerView.Adapter<PhotoHolder> {

        private List<GalleryItem> mGalleryItems;

        public PhotoAdapter(List<GalleryItem> galleryItems) {
            mGalleryItems = galleryItems;
        }


        @NonNull
        @Override
        public PhotoHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(getActivity());
            View view = inflater.inflate(R.layout.galery_item, viewGroup, false);
            return new PhotoHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoHolder photoHolder, int position) {
            GalleryItem galleryItem = mGalleryItems.get(position);
            Drawable currentImage = getResources().getDrawable(R.drawable.bill_up_close);
            PhotoCache photoCache = PhotoCache.get(getContext());
            if (photoCache.getBitmapFromMemCache(galleryItem.getUrl()) != null) {
                currentImage = new BitmapDrawable(getResources(), photoCache.getBitmapFromMemCache(galleryItem.getUrl()));
                Log.i(TAG, "Found in cache, no need to download image");
            } else {
                mThumbnailDownloader.queneThumbnail(photoHolder, galleryItem.getUrl());
            }
            photoHolder.bindDrawable(currentImage);
        }

        @Override
        public int getItemCount() {
            return mGalleryItems.size();
        }
    }
}
