/*
 *   Copyright (C) 2015. Kumaresan Rajeswaran
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package com.android.leanlauncher;

import android.appwidget.AppWidgetProviderInfo;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteCantOpenDatabaseException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDiskIOException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteReadOnlyDatabaseException;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.support.v4.util.ArrayMap;
import android.util.Log;
import com.android.leanlauncher.compat.AppWidgetManagerCompat;
import com.android.leanlauncher.compat.UserHandleCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class WidgetPreviewLoader {

    private static abstract class SoftReferenceThreadLocal<T> {
        private ThreadLocal<SoftReference<T>> mThreadLocal;
        public SoftReferenceThreadLocal() {
            mThreadLocal = new ThreadLocal<SoftReference<T>>();
        }

        abstract T initialValue();

        public void set(T t) {
            mThreadLocal.set(new SoftReference<T>(t));
        }

        public T get() {
            SoftReference<T> reference = mThreadLocal.get();
            T obj;
            if (reference == null) {
                obj = initialValue();
                mThreadLocal.set(new SoftReference<T>(obj));
                return obj;
            } else {
                obj = reference.get();
                if (obj == null) {
                    obj = initialValue();
                    mThreadLocal.set(new SoftReference<T>(obj));
                }
                return obj;
            }
        }
    }

    private static class CanvasCache extends SoftReferenceThreadLocal<Canvas> {
        @Override
        protected Canvas initialValue() {
            return new Canvas();
        }
    }

    private static class PaintCache extends SoftReferenceThreadLocal<Paint> {
        @Override
        protected Paint initialValue() {
            return null;
        }
    }

    private static class BitmapCache extends SoftReferenceThreadLocal<Bitmap> {
        @Override
        protected Bitmap initialValue() {
            return null;
        }
    }

    private static class RectCache extends SoftReferenceThreadLocal<Rect> {
        @Override
        protected Rect initialValue() {
            return new Rect();
        }
    }

    private static class BitmapFactoryOptionsCache extends
            SoftReferenceThreadLocal<BitmapFactory.Options> {
        @Override
        protected BitmapFactory.Options initialValue() {
            return new BitmapFactory.Options();
        }
    }

    private static final String TAG = "WidgetPreviewLoader";
    private static final String ANDROID_INCREMENTAL_VERSION_NAME_KEY = "android.incremental.version";

    private static final float WIDGET_PREVIEW_ICON_PADDING_PERCENTAGE = 0.25f;
    private static final HashSet<String> sInvalidPackages = new HashSet<String>();

    // Used for drawing shortcut previews
    private final BitmapCache mCachedShortcutPreviewBitmap = new BitmapCache();
    private final PaintCache mCachedShortcutPreviewPaint = new PaintCache();
    private final CanvasCache mCachedShortcutPreviewCanvas = new CanvasCache();

    // Used for drawing widget previews
    private final CanvasCache mCachedAppWidgetPreviewCanvas = new CanvasCache();
    private final RectCache mCachedAppWidgetPreviewSrcRect = new RectCache();
    private final RectCache mCachedAppWidgetPreviewDestRect = new RectCache();
    private final PaintCache mCachedAppWidgetPreviewPaint = new PaintCache();
    private final PaintCache mDefaultAppWidgetPreviewPaint = new PaintCache();
    private final BitmapFactoryOptionsCache mCachedBitmapFactoryOptions = new BitmapFactoryOptionsCache();

    private final ArrayMap<String, WeakReference<Bitmap>> mLoadedPreviews = new ArrayMap<>();
    private final ArrayList<SoftReference<Bitmap>> mUnusedBitmaps = new ArrayList<SoftReference<Bitmap>>();

    private final Context mContext;
    private final int mAppIconSize;
    private final IconCache mIconCache;
    private final AppWidgetManagerCompat mManager;

    private int mPreviewBitmapWidth;
    private int mPreviewBitmapHeight;
    private String mSize;
    private PagedViewCellLayout mWidgetSpacingLayout;

    private String mCachedSelectQuery;


    private CacheDb mDb;

    private final MainThreadExecutor mMainThreadExecutor = new MainThreadExecutor();

    public WidgetPreviewLoader(Context context) {
        LauncherAppState app = LauncherAppState.getInstance();
        DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();

        mContext = context;
        mAppIconSize = grid.iconSizePx;
        mIconCache = app.getIconCache();
        mManager = AppWidgetManagerCompat.getInstance(context);

        mDb = app.getWidgetPreviewCacheDb();

        SharedPreferences sp = context.getSharedPreferences(
                LauncherAppState.getSharedPreferencesKey(), Context.MODE_PRIVATE);
        final String lastVersionName = sp.getString(ANDROID_INCREMENTAL_VERSION_NAME_KEY, null);
        final String versionName = android.os.Build.VERSION.INCREMENTAL;
        if (!versionName.equals(lastVersionName)) {
            try {
                // clear all the previews whenever the system version changes, to ensure that
                // previews are up-to-date for any apps that might have been updated with the system
                clearDb();
            } catch (SQLiteReadOnlyDatabaseException e) {
                Log.e(TAG, "Error clearing widget preview db : " + e);
            } finally {
                SharedPreferences.Editor editor = sp.edit();
                editor.putString(ANDROID_INCREMENTAL_VERSION_NAME_KEY, versionName);
                editor.apply();
            }
        }
    }

    public void recreateDb() {
        LauncherAppState app = LauncherAppState.getInstance();
        app.recreateWidgetPreviewDb();
        mDb = app.getWidgetPreviewCacheDb();
    }

    public void setPreviewSize(int previewWidth, int previewHeight,
            PagedViewCellLayout widgetSpacingLayout) {
        mPreviewBitmapWidth = previewWidth;
        mPreviewBitmapHeight = previewHeight;
        mSize = previewWidth + "x" + previewHeight;
        mWidgetSpacingLayout = widgetSpacingLayout;
    }

    public Bitmap getPreview(final Object o) {
        final String name = getObjectName(o);
        final String packageName = getObjectPackage(o);
        // check if the package is valid
        synchronized(sInvalidPackages) {
            boolean packageValid = !sInvalidPackages.contains(packageName);
            if (!packageValid) {
                return null;
            }
        }
        synchronized(mLoadedPreviews) {
            // check if it exists in our existing cache
            if (mLoadedPreviews.containsKey(name)) {
                WeakReference<Bitmap> bitmapReference = mLoadedPreviews.get(name);
                Bitmap bitmap = bitmapReference.get();
                if (bitmap != null) {
                    return bitmap;
                }
            }
        }

        Bitmap unusedBitmap = null;
        synchronized(mUnusedBitmaps) {
            // not in cache; we need to load it from the db
            while (unusedBitmap == null && mUnusedBitmaps.size() > 0) {
                Bitmap candidate = mUnusedBitmaps.remove(0).get();
                if (candidate != null && candidate.isMutable() &&
                        candidate.getWidth() == mPreviewBitmapWidth &&
                        candidate.getHeight() == mPreviewBitmapHeight) {
                    unusedBitmap = candidate;
                }
            }
            if (unusedBitmap != null) {
                final Canvas c = mCachedAppWidgetPreviewCanvas.get();
                c.setBitmap(unusedBitmap);
                c.drawColor(0, PorterDuff.Mode.CLEAR);
                c.setBitmap(null);
            }
        }

        if (unusedBitmap == null) {
            unusedBitmap = Bitmap.createBitmap(mPreviewBitmapWidth, mPreviewBitmapHeight,
                    Bitmap.Config.ARGB_8888);
        }
        Bitmap preview = readFromDb(name, unusedBitmap);

        if (preview != null) {
            synchronized(mLoadedPreviews) {
                mLoadedPreviews.put(name, new WeakReference<Bitmap>(preview));
            }
            return preview;
        } else {
            // it's not in the db... we need to generate it
            final Bitmap generatedPreview = generatePreview(o, unusedBitmap);
            preview = generatedPreview;
            if (preview != unusedBitmap) {
                throw new RuntimeException("generatePreview is not recycling the bitmap " + o);
            }

            synchronized(mLoadedPreviews) {
                mLoadedPreviews.put(name, new WeakReference<Bitmap>(preview));
            }

            // write to db on a thread pool... this can be done lazily and improves the performance
            // of the first time widget previews are loaded
            new AsyncTask<Void, Void, Void>() {
                public Void doInBackground(Void ... args) {
                    writeToDb(o, generatedPreview);
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);

            return preview;
        }
    }

    public void recycleBitmap(Object o, Bitmap bitmapToRecycle) {
        String name = getObjectName(o);
        synchronized (mLoadedPreviews) {
            if (mLoadedPreviews.containsKey(name)) {
                Bitmap b = mLoadedPreviews.get(name).get();
                if (b == bitmapToRecycle) {
                    mLoadedPreviews.remove(name);
                    if (bitmapToRecycle.isMutable()) {
                        synchronized (mUnusedBitmaps) {
                            mUnusedBitmaps.add(new SoftReference<Bitmap>(b));
                        }
                    }
                } else {
                    throw new RuntimeException("Bitmap passed in doesn't match up");
                }
            }
        }
    }

    static class CacheDb extends SQLiteOpenHelper {
        final static int DB_VERSION = 2;
        final static String TABLE_NAME = "shortcut_and_widget_previews";
        final static String COLUMN_NAME = "name";
        final static String COLUMN_SIZE = "size";
        final static String COLUMN_PREVIEW_BITMAP = "preview_bitmap";
        Context mContext;

        public CacheDb(Context context) {
            super(context, new File(context.getCacheDir(),
                    LauncherFiles.WIDGET_PREVIEWS_DB).getPath(), null, DB_VERSION);
            // Store the context for later use
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                    COLUMN_NAME + " TEXT NOT NULL, " +
                    COLUMN_SIZE + " TEXT NOT NULL, " +
                    COLUMN_PREVIEW_BITMAP + " BLOB NOT NULL, " +
                    "PRIMARY KEY (" + COLUMN_NAME + ", " + COLUMN_SIZE + ") " +
                    ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion != newVersion) {
                // Delete all the records; they'll be repopulated as this is a cache
                db.execSQL("DELETE FROM " + TABLE_NAME);
            }
        }
    }

    private static final String WIDGET_PREFIX = "Widget:";

    private static String getObjectName(Object o) {
        // should cache the string builder
        StringBuilder sb = new StringBuilder();
        String output = null;
        if (o instanceof AppWidgetProviderInfo) {
            sb.append(WIDGET_PREFIX);
            sb.append(o.toString());
            output = sb.toString();
            sb.setLength(0);
        }
        return output;
    }

    private String getObjectPackage(Object o) {
        if (o instanceof AppWidgetProviderInfo) {
            return ((AppWidgetProviderInfo) o).provider.getPackageName();
        } else {
            return null;
        }
    }

    private void writeToDb(Object o, Bitmap preview) {
        String name = getObjectName(o);
        SQLiteDatabase db = mDb.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(CacheDb.COLUMN_NAME, name);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        preview.compress(Bitmap.CompressFormat.PNG, 100, stream);
        values.put(CacheDb.COLUMN_PREVIEW_BITMAP, stream.toByteArray());
        values.put(CacheDb.COLUMN_SIZE, mSize);
        try {
            db.insert(CacheDb.TABLE_NAME, null, values);
        } catch (SQLiteDiskIOException e) {
            recreateDb();
        } catch (SQLiteCantOpenDatabaseException e) {
            throw e;
        }
    }

    private void clearDb() {
        SQLiteDatabase db = mDb.getWritableDatabase();
        // Delete everything
        try {
            db.delete(CacheDb.TABLE_NAME, null, null);
        } catch (SQLiteDiskIOException e) {
        } catch (SQLiteCantOpenDatabaseException e) {
            throw e;
        }
    }

    public static void removePackageFromDb(final CacheDb cacheDb, final String packageName) {
        synchronized(sInvalidPackages) {
            sInvalidPackages.add(packageName);
        }
        new AsyncTask<Void, Void, Void>() {
            public Void doInBackground(Void ... args) {
                SQLiteDatabase db = cacheDb.getWritableDatabase();
                try {
                    db.delete(CacheDb.TABLE_NAME,
                            CacheDb.COLUMN_NAME + " LIKE ? OR " +
                            CacheDb.COLUMN_NAME + " LIKE ?", // SELECT query
                            new String[] {
                                    WIDGET_PREFIX + packageName + "/%"
                            } // args to SELECT query
                    );
                } catch (SQLiteDiskIOException ignored) {
                }
                synchronized(sInvalidPackages) {
                    sInvalidPackages.remove(packageName);
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
    }

    private static void removeItemFromDb(final CacheDb cacheDb, final String objectName) {
        new AsyncTask<Void, Void, Void>() {
            public Void doInBackground(Void ... args) {
                SQLiteDatabase db = cacheDb.getWritableDatabase();
                try {
                    db.delete(CacheDb.TABLE_NAME,
                            CacheDb.COLUMN_NAME + " = ? ", // SELECT query
                            new String[] { objectName }); // args to SELECT query
                } catch (SQLiteDiskIOException ignored) {
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
    }

    private Bitmap readFromDb(String name, Bitmap b) {
        if (mCachedSelectQuery == null) {
            mCachedSelectQuery = CacheDb.COLUMN_NAME + " = ? AND " +
                    CacheDb.COLUMN_SIZE + " = ?";
        }
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor result;
        try {
            result = db.query(CacheDb.TABLE_NAME,
                    new String[] { CacheDb.COLUMN_PREVIEW_BITMAP }, // cols to return
                    mCachedSelectQuery, // select query
                    new String[] { name, mSize }, // args to select query
                    null,
                    null,
                    null,
                    null);
        } catch (SQLiteDiskIOException e) {
            recreateDb();
            return null;
        } catch (SQLiteCantOpenDatabaseException e) {
            throw e;
        }
        if (result.getCount() > 0) {
            result.moveToFirst();
            byte[] blob = result.getBlob(0);
            result.close();
            final BitmapFactory.Options opts = mCachedBitmapFactoryOptions.get();
            opts.inBitmap = b;
            opts.inSampleSize = 1;
            try {
                return BitmapFactory.decodeByteArray(blob, 0, blob.length, opts);
            } catch (IllegalArgumentException e) {
                removeItemFromDb(mDb, name);
                return null;
            }
        } else {
            result.close();
            return null;
        }
    }

    private Bitmap generatePreview(Object info, Bitmap preview) {
        if (preview != null &&
                (preview.getWidth() != mPreviewBitmapWidth ||
                preview.getHeight() != mPreviewBitmapHeight)) {
            throw new RuntimeException("Improperly sized bitmap passed as argument");
        }
        if (info instanceof AppWidgetProviderInfo) {
            return generateWidgetPreview((AppWidgetProviderInfo) info, preview);
        } else {
            return null;
        }
    }

    public Bitmap generateWidgetPreview(AppWidgetProviderInfo info, Bitmap preview) {
        int[] cellSpans = Launcher.getSpanForWidget(mContext, info);
        int maxWidth = maxWidthForWidgetPreview(cellSpans[0]);
        int maxHeight = maxHeightForWidgetPreview(cellSpans[1]);
        return generateWidgetPreview(info, cellSpans[0], cellSpans[1],
                maxWidth, maxHeight, preview, null);
    }

    public int maxWidthForWidgetPreview(int spanX) {
        return Math.min(mPreviewBitmapWidth,
                mWidgetSpacingLayout.estimateCellWidth(spanX));
    }

    public int maxHeightForWidgetPreview(int spanY) {
        return Math.min(mPreviewBitmapHeight,
                mWidgetSpacingLayout.estimateCellHeight(spanY));
    }

    public Bitmap generateWidgetPreview(AppWidgetProviderInfo info, int cellHSpan, int cellVSpan,
            int maxPreviewWidth, int maxPreviewHeight, Bitmap preview, int[] preScaledWidthOut) {
        // Load the preview image if possible
        if (maxPreviewWidth < 0) maxPreviewWidth = Integer.MAX_VALUE;

        Drawable drawable = null;
        if (info.previewImage != 0) {
            drawable = mManager.loadPreview(info);
            if (drawable != null) {
                drawable = mutateOnMainThread(drawable);
            } else {
                Log.w(TAG, "Can't load widget preview drawable 0x" +
                        Integer.toHexString(info.previewImage) + " for provider: " + info.provider);
            }
        }

        int previewWidth;
        int previewHeight;
        Bitmap defaultPreview = null;
        boolean widgetPreviewExists = (drawable != null);
        if (widgetPreviewExists) {
            previewWidth = drawable.getIntrinsicWidth();
            previewHeight = drawable.getIntrinsicHeight();
        } else {
            // Generate a preview image if we couldn't load one
            if (cellHSpan < 1) cellHSpan = 1;
            if (cellVSpan < 1) cellVSpan = 1;

            // This Drawable is not directly drawn, so there's no need to mutate it.
            BitmapDrawable previewDrawable = (BitmapDrawable) mContext.getResources()
                    .getDrawable(R.drawable.widget_tile);
            final int previewDrawableWidth = previewDrawable
                    .getIntrinsicWidth();
            final int previewDrawableHeight = previewDrawable
                    .getIntrinsicHeight();
            previewWidth = previewDrawableWidth * cellHSpan;
            previewHeight = previewDrawableHeight * cellVSpan;

            defaultPreview = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
            final Canvas c = mCachedAppWidgetPreviewCanvas.get();
            c.setBitmap(defaultPreview);
            Paint p = mDefaultAppWidgetPreviewPaint.get();
            if (p == null) {
                p = new Paint();
                p.setShader(new BitmapShader(previewDrawable.getBitmap(),
                        Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
                mDefaultAppWidgetPreviewPaint.set(p);
            }
            final Rect dest = mCachedAppWidgetPreviewDestRect.get();
            dest.set(0, 0, previewWidth, previewHeight);
            c.drawRect(dest, p);
            c.setBitmap(null);

            // Draw the icon in the top left corner
            int minOffset = (int) (mAppIconSize * WIDGET_PREVIEW_ICON_PADDING_PERCENTAGE);
            int smallestSide = Math.min(previewWidth, previewHeight);
            float iconScale = Math.min((float) smallestSide
                    / (mAppIconSize + 2 * minOffset), 1f);

            try {
                Bitmap icon = mIconCache.getIconForComponent(info.configure, info.getProfile());
                if (icon != null) {
                    int hoffset = (int) ((previewDrawableWidth - mAppIconSize * iconScale) / 2);
                    int yoffset = (int) ((previewDrawableHeight - mAppIconSize * iconScale) / 2);
                    renderBitmapIconOnPreview(icon, defaultPreview, hoffset,
                            yoffset, (int) (mAppIconSize * iconScale),
                            (int) (mAppIconSize * iconScale));
                }
            } catch (Resources.NotFoundException ignored) {
            }
        }

        // Scale to fit width only - let the widget preview be clipped in the
        // vertical dimension
        float scale = 1f;
        if (preScaledWidthOut != null) {
            preScaledWidthOut[0] = previewWidth;
        }
        if (previewWidth > maxPreviewWidth) {
            scale = maxPreviewWidth / (float) previewWidth;
        }
        if (scale != 1f) {
            previewWidth = (int) (scale * previewWidth);
            previewHeight = (int) (scale * previewHeight);
        }

        // If a bitmap is passed in, we use it; otherwise, we create a bitmap of the right size
        if (preview == null) {
            preview = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        }

        // Draw the scaled preview into the final bitmap
        int x = (preview.getWidth() - previewWidth) / 2;
        if (widgetPreviewExists) {
            renderDrawableToBitmap(drawable, preview, x, 0, previewWidth,
                    previewHeight);
        } else {
            final Canvas c = mCachedAppWidgetPreviewCanvas.get();
            final Rect src = mCachedAppWidgetPreviewSrcRect.get();
            final Rect dest = mCachedAppWidgetPreviewDestRect.get();
            c.setBitmap(preview);
            src.set(0, 0, defaultPreview.getWidth(), defaultPreview.getHeight());
            dest.set(x, 0, x + previewWidth, previewHeight);

            Paint p = mCachedAppWidgetPreviewPaint.get();
            if (p == null) {
                p = new Paint();
                p.setFilterBitmap(true);
                mCachedAppWidgetPreviewPaint.set(p);
            }
            c.drawBitmap(defaultPreview, src, dest, p);
            c.setBitmap(null);
        }
        return preview;
    }

    private static void renderBitmapIconOnPreview(
            Bitmap icon, Bitmap preview, int x, int y, int w, int h) {
        if (preview != null) {
            final Canvas c = new Canvas(preview);
            icon = Bitmap.createScaledBitmap(icon, w, h, false);
            c.drawBitmap(icon, x, y, null);
            c.setBitmap(null);
        }
    }

    private static void renderDrawableToBitmap(
            Drawable d, Bitmap bitmap, int x, int y, int w, int h) {
        if (bitmap != null) {
            final Canvas c = new Canvas(bitmap);
            Rect oldBounds = d.copyBounds();
            d.setBounds(x, y, x + w, y + h);
            d.draw(c);
            d.setBounds(oldBounds); // Restore the bounds
            c.setBitmap(null);
        }
    }

    private Drawable mutateOnMainThread(final Drawable drawable) {
        try {
            return mMainThreadExecutor.submit(new Callable<Drawable>() {
                @Override
                public Drawable call() throws Exception {
                    return drawable.mutate();
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
