/*
 * Copyright (C) 2015 Kumaresan Rajeswaran
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.leanlauncher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.util.ArrayMap;
import android.text.TextUtils;
import android.util.Log;

import com.android.leanlauncher.compat.LauncherActivityInfoCompat;
import com.android.leanlauncher.compat.LauncherAppsCompat;
import com.android.leanlauncher.compat.UserHandleCompat;
import com.android.leanlauncher.compat.UserManagerCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.Random;

import static android.graphics.PorterDuff.Mode.DST_IN;
import static android.graphics.PorterDuff.Mode.DST_OUT;

/**
 * Cache of application icons.  Icons can be made from any thread.
 */
public class IconCache {

    private static final String TAG = "Launcher.IconCache";

    private static final int INITIAL_ICON_CACHE_CAPACITY = 50;

    // Empty class name is used for storing package default entry.
    private static final String EMPTY_CLASS_NAME = ".";

    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static final String NOVA_LAUNCHER_THEME_NAME = "com.gau.go.launcherex.theme";
    private static final String GO_LAUNCHER_THEME_NAME = "com.gau.go.launcherex.theme";

    private static class CacheEntry {
        public Bitmap icon;
        public CharSequence title;
        public CharSequence contentDescription;
    }

    private static class CacheKey {
        public ComponentName componentName;
        public UserHandleCompat user;

        CacheKey(ComponentName componentName, UserHandleCompat user) {
            this.componentName = componentName;
            this.user = user;
        }

        @Override
        public int hashCode() {
            return componentName.hashCode() + user.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            CacheKey other = (CacheKey) o;
            return other.componentName.equals(componentName) && other.user.equals(user);
        }
    }

    private final ArrayMap<UserHandleCompat, Bitmap> mDefaultIcons = new ArrayMap<>();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManagerCompat mUserManager;
    private final LauncherAppsCompat mLauncherApps;
    private final HashMap<CacheKey, CacheEntry> mCache = new HashMap<>(INITIAL_ICON_CACHE_CAPACITY);
    private int mIconDpi;
    private String mCurrentIconTheme = "";
    private ArrayList<Bitmap> mIconBackgrounds = new ArrayList<>();
    private final ArrayMap<ComponentName, String> mIconPackDrawables = new ArrayMap<>();
    private Bitmap mIconMask;
    private Bitmap mIconFront;
    private float mIconScaleFactor;
    private Resources mIconPackRes;

    public IconCache(Context context) {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        mContext = context;
        mPackageManager = context.getPackageManager();
        mUserManager = UserManagerCompat.getInstance(mContext);
        mLauncherApps = LauncherAppsCompat.getInstance(mContext);
        mIconDpi = activityManager.getLauncherLargeIconDensity();

        // need to set mIconDpi before getting default icon
        UserHandleCompat myUser = UserHandleCompat.myUserHandle();
        mDefaultIcons.put(myUser, makeDefaultIcon(myUser));
        mCurrentIconTheme = PreferenceManager.getDefaultSharedPreferences(context).
                getString(context.getString(R.string.pref_icon_theme_key), "");
        getIconPackResources();
    }

    public String getCurrentIconTheme() {
        return mCurrentIconTheme;
    }

    public void setCurrentIconTheme(String iconTheme) {
        mCurrentIconTheme = iconTheme;
    }

    private Resources getIconPackResources() {
        if (mIconPackRes != null) {
            return mIconPackRes;
        }

        if (TextUtils.isEmpty(mCurrentIconTheme)) {
            return null;
        }

        try {
            mIconPackRes = mPackageManager.getResourcesForApplication(mCurrentIconTheme);
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Can't load icon theme: " + mCurrentIconTheme);
            return null;
        }
        return mIconPackRes;
    }

    public ArrayMap<String, String> getAvailableIconPacks() {
        ArrayMap<String, String> availableIconPacks = new ArrayMap<>();

        PackageManager pm = mContext.getPackageManager();

        // fetch installed icon packs for popular launchers
        Intent novaIntent = new Intent(Intent.ACTION_MAIN);
        novaIntent.addCategory(NOVA_LAUNCHER_THEME_NAME);
        List<ResolveInfo> novaTheme = pm.queryIntentActivities(novaIntent, PackageManager.GET_META_DATA);
        List<ResolveInfo> goTheme = pm.queryIntentActivities(new Intent(GO_LAUNCHER_THEME_NAME), PackageManager.GET_META_DATA);

        // merge those lists
        List<ResolveInfo> rinfo = new ArrayList<>(novaTheme);
        rinfo.addAll(goTheme);

        for (ResolveInfo ri : rinfo) {
            String packageName = ri.activityInfo.packageName;

            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
                String label = pm.getApplicationLabel(ai).toString();
                Log.d(TAG, "Icon package = " + packageName + " title " + label);
                availableIconPacks.put(packageName, label);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Package not found = " + e);
            }
        }

        return availableIconPacks;
    }


    // should be called in background
    // gracelessly stolen from http://stackoverflow.com/a/31512017
    public void loadIconPackDrawables(boolean forceReload) {
        final long t = SystemClock.uptimeMillis();
        synchronized (mIconPackDrawables) {
            if (!forceReload && mIconPackDrawables.size() > 0) {
                return;
            }

            // load appfilter.xml from the icon pack package
            try {
                XmlPullParser xpp = null;

                final Resources iconPackRes = getIconPackResources();
                if (iconPackRes == null) {
                    return;
                }

                int appfilterid = iconPackRes.getIdentifier("appfilter", "xml", mCurrentIconTheme);
                if (appfilterid > 0) {
                    xpp = iconPackRes.getXml(appfilterid);
                } else {
                    // no resource found, try to open it from assets folder
                    try {
                        InputStream is = iconPackRes.getAssets().open("appfilter.xml");

                        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                        factory.setNamespaceAware(true);
                        xpp = factory.newPullParser();
                        xpp.setInput(is, "utf-8");
                    } catch (IOException e) {
                        Log.d(TAG, "Can't find appfilter.xml file in : " + mCurrentIconTheme);
                    }
                }

                if (xpp != null) {
                    int eventType = xpp.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            if ("item".equals(xpp.getName())) {
                                String componentName = null;
                                String drawableName = null;

                                for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                    if ("component".equals(xpp.getAttributeName(i))) {
                                        componentName = xpp.getAttributeValue(i);
                                    } else if ("drawable".equals(xpp.getAttributeName(i))) {
                                        drawableName = xpp.getAttributeValue(i);
                                    }
                                }

                                if (TextUtils.isEmpty(componentName) || TextUtils.isEmpty(drawableName)) {
                                    eventType = xpp.next();
                                    continue;
                                }

                                try {
                                    componentName = componentName.substring(componentName.indexOf('{') + 1, componentName.indexOf('}'));
                                } catch (StringIndexOutOfBoundsException e) {
                                    Log.d(TAG, "Can't parse icon for package = " + componentName);
                                    eventType = xpp.next();
                                    continue;
                                }

                                ComponentName componentNameKey = ComponentName.unflattenFromString(componentName);
                                if (componentNameKey != null) {
                                    mIconPackDrawables.put(componentNameKey, drawableName);
                                } else {
                                    Log.d(TAG, "ComponentName can't be obtained from: " + componentName);
                                }
                            } else if ("iconback".equals(xpp.getName())) {
                                for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                    if (xpp.getAttributeName(i).startsWith("img")) {
                                        mIconBackgrounds.add(
                                                loadBitmapFromIconPack(xpp.getAttributeValue(i)));
                                    }
                                }
                            } else if ("iconmask".equals(xpp.getName())) {
                                if (xpp.getAttributeCount() > 0 && "img1".equals(xpp.getAttributeName(0))) {
                                    mIconMask = loadBitmapFromIconPack(xpp.getAttributeValue(0));
                                }
                            } else if ("iconupon".equals(xpp.getName())) {
                                if (xpp.getAttributeCount() > 0 && "img1".equals(xpp.getAttributeName(0))) {
                                    mIconFront = loadBitmapFromIconPack(xpp.getAttributeValue(0));
                                }
                            } else if ("scale".equals(xpp.getName())) {
                                if (xpp.getAttributeCount() > 0 && "factor".equals(xpp.getAttributeName(0))) {
                                    mIconScaleFactor = Float.valueOf(xpp.getAttributeValue(0));
                                }
                            }
                        }
                        eventType = xpp.next();
                    }
                }
                Log.d(TAG, "Finished parsing icon pack: " + (SystemClock.uptimeMillis()-t) + "ms");
            } catch (XmlPullParserException e) {
                Log.d(TAG, "Cannot parse icon pack appfilter.xml" + e);
            } catch (IOException e) {
                Log.d(TAG, "Exception loading icon pack " + e);
            }
        }
    }

    private Drawable getFullResDefaultActivityIcon() {
        return getFullResDefaultIcon(Resources.getSystem(), android.R.mipmap.sym_def_app_icon);
    }

    private Drawable getFullResDefaultIcon(Resources resources, int iconId) {
        Drawable d;
        try {
            d = resources.getDrawableForDensity(iconId, mIconDpi);
        } catch (Resources.NotFoundException e) {
            d = null;
        }

        return (d != null) ? d : getFullResDefaultActivityIcon();
    }

    private Bitmap makeDefaultIcon(UserHandleCompat user) {
        Drawable unbadged = getFullResDefaultActivityIcon();
        Drawable d = mUserManager.getBadgedDrawableForUser(unbadged, user);
        Bitmap b = Bitmap.createBitmap(Math.max(d.getIntrinsicWidth(), 1),
                Math.max(d.getIntrinsicHeight(), 1),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, b.getWidth(), b.getHeight());
        d.draw(c);
        c.setBitmap(null);
        return b;
    }

    /**
     * Remove any records for the supplied ComponentName.
     */
    public synchronized void remove(ComponentName componentName, UserHandleCompat user) {
        mCache.remove(new CacheKey(componentName, user));
    }

    /**
     * Remove any records for the supplied package name.
     */
    public synchronized void remove(String packageName, UserHandleCompat user) {
        HashSet<CacheKey> forDeletion = new HashSet<CacheKey>();
        for (CacheKey key: mCache.keySet()) {
            if (key.componentName.getPackageName().equals(packageName)
                    && key.user.equals(user)) {
                forDeletion.add(key);
            }
        }
        for (CacheKey condemned: forDeletion) {
            mCache.remove(condemned);
        }
    }

    /**
     * Empty out the cache.
     */
    public synchronized void flush() {
        mCache.clear();
        flushIconPack();
    }

    private void flushIconPack() {
        mIconPackRes = null;
        mIconBackgrounds.clear();
        mIconPackDrawables.clear();
        mIconFront = null;
        mIconMask = null;
        mIconScaleFactor = 0;
    }

    /**
     * Empty out the cache that aren't of the correct grid size
     */
    public synchronized void flushInvalidIcons(DeviceProfile grid) {
        Iterator<Entry<CacheKey, CacheEntry>> it = mCache.entrySet().iterator();
        while (it.hasNext()) {
            final CacheEntry e = it.next().getValue();
            if ((e.icon != null) && (e.icon.getWidth() < grid.iconSizePx
                    || e.icon.getHeight() < grid.iconSizePx)) {
                it.remove();
            }
        }
    }

    /**
     * Fill in "application" with the icon and label for "info."
     */
    public synchronized void fetchAppIcon(AppInfo appInfo, LauncherActivityInfoCompat info,
                                          Map<Object, CharSequence> labelCache) {
        CacheEntry entry = cacheLocked(appInfo, appInfo.componentName, info, info.getUser(), labelCache, false);

        appInfo.title = entry.title;
        appInfo.contentDescription = entry.contentDescription;
    }

    public synchronized Bitmap getIconForComponent(ComponentName componentName, UserHandleCompat user) {
        if (componentName == null || TextUtils.isEmpty(componentName.getPackageName())) {
            // happens on first load sometimes
            return null;
        }
        CacheEntry entry = getEntryForPackage(componentName.getPackageName(), user);
        return entry.icon;
    }

    public Bitmap getAppIcon(ItemInfo info) {
        if (info == null) {
            return null;
        }

        Intent appIntent;
        if (info instanceof ShortcutInfo) {
            appIntent = ((ShortcutInfo) info).intent;
        } else if (info instanceof AppInfo) {
            appIntent = ((AppInfo) info).intent;
        } else {
            return null;
        }

        return getIcon(appIntent, info.user, info);
    }

    public synchronized Bitmap getIcon(Intent intent, UserHandleCompat user, ItemInfo info) {
        ComponentName component = intent.getComponent();
        // null info means not installed, but if we have a component from the intent then
        // we should still look in the cache for restored app icons.
        if (component == null) {
            return getDefaultUserIcon(user);
        }

        LauncherActivityInfoCompat launcherActInfo = mLauncherApps.resolveActivity(intent, user);
        CacheEntry entry = cacheLocked(info, component, launcherActInfo, user, null, true);
        return entry.icon;
    }

    private synchronized Bitmap getDefaultUserIcon(UserHandleCompat user) {
        if (!mDefaultIcons.containsKey(user)) {
            mDefaultIcons.put(user, makeDefaultIcon(user));
        }
        return mDefaultIcons.get(user);
    }

    /**
     * Retrieves the entry from the cache. If the entry is not present, it creates a new entry.
     * This method is not thread safe, it must be called from a synchronized method.
     */
    private CacheEntry cacheLocked(ItemInfo itemInfo, ComponentName componentName, LauncherActivityInfoCompat info,
                                   UserHandleCompat user, Map<Object, CharSequence> labelCache, boolean usePackageIcon) {
        CacheKey cacheKey = new CacheKey(componentName, user);
        CacheEntry entry = mCache.get(cacheKey);
        if (entry == null || entry.icon == null) {
            entry = new CacheEntry();

            if (info != null) {
                ComponentName labelKey = info.getComponentName();
                if (labelCache != null && labelCache.containsKey(labelKey)) {
                    entry.title = labelCache.get(labelKey).toString();
                } else {
                    entry.title = info.getLabel().toString();
                    if (labelCache != null) {
                        labelCache.put(labelKey, entry.title);
                    }
                }

                entry.contentDescription = mUserManager.getBadgedLabelForUser(entry.title, user);
                Drawable defaultDrawable = info.getBadgedIcon(mIconDpi);
                if (itemInfo != null && itemInfo.iconResource != null && itemInfo.iconResource.resourceName != null
                        && mCurrentIconTheme.equals(itemInfo.iconResource.packageName)) {
                    // no icon theme changes, so fetch from theme directly
                    entry.icon = createIconBitmapFromTheme(itemInfo.iconResource.resourceName, defaultDrawable);
                } else if (!TextUtils.isEmpty(mCurrentIconTheme)) {
                    entry.icon = createNewIconBitmap(itemInfo, componentName.getPackageName(), componentName.getClassName(),
                            defaultDrawable);
                }

                if (entry.icon == null) {
                    // pick default icon
                    entry.icon = Utilities.createIconBitmap(defaultDrawable, mContext);
                }

                mCache.put(cacheKey, entry);
            } else {
                entry.title = "";
                if (usePackageIcon) {
                    entry = getEntryForPackage(componentName.getPackageName(), user);
                    Log.d(TAG, "using package default icon for " + componentName.toShortString());
                }
                if (entry.icon == null) {
                    Log.d(TAG, "using default icon for " + componentName.toShortString());
                    entry.icon = getDefaultUserIcon(user);
                }
            }
        }
        return entry;
    }

    private Bitmap createNewIconBitmap(ItemInfo info, String packageName, String className, Drawable defaultDrawable) {
        String drawableName = findDrawableFromIconPack(packageName, className);

        // save the found drawable back to item
        if (info != null && drawableName != null) {
            info.iconResource = new Intent.ShortcutIconResource();
            info.iconResource.packageName = mCurrentIconTheme;
            info.iconResource.resourceName = drawableName;
        }

        return createIconBitmapFromTheme(drawableName, defaultDrawable);
    }

    private String findDrawableFromIconPack(String packageName, String className) {
        if (mIconPackDrawables == null || mIconPackDrawables.size() == 0) {
            return null;
        }

        String drawableName = null;
        for (ComponentName key : mIconPackDrawables.keySet()) {
            if (key.getPackageName().equalsIgnoreCase(packageName)) {
                drawableName = mIconPackDrawables.get(key);
                if (!TextUtils.isEmpty(drawableName)) {
                    Log.d(TAG, drawableName + " -- found for -- " + packageName);

                    if (className == null || className.equalsIgnoreCase(key.getClassName())) {
                        // we've found it
                        break;
                    }
                }
            }
        }

        return drawableName;
    }

    private Drawable loadDrawableFromIconPack(String drawableName) {
        if (TextUtils.isEmpty(drawableName) || mIconPackRes == null) {
            return null;
        }

        int id = mIconPackRes.getIdentifier(drawableName, "drawable", mCurrentIconTheme);
        if (id > 0) {
            return mIconPackRes.getDrawable(id);
        }
        return null;
    }

    private Bitmap loadBitmapFromIconPack(String drawableName) {
        Drawable drawable = loadDrawableFromIconPack(drawableName);

        if (drawable != null) {
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            }

            return Utilities.createIconBitmap(drawable, mContext);
        }
        return null;
    }

    public Bitmap createIconBitmapFromTheme(String iconDrawableName, Drawable defaultDrawable) {
        Bitmap icon = loadBitmapFromIconPack(iconDrawableName);

        if (icon == null) {
            Log.d(TAG, "Using default icon, can't find icon drawable: " + iconDrawableName + " in " + mCurrentIconTheme);
            icon = ((BitmapDrawable) defaultDrawable).getBitmap();
        }


        if (mIconBackgrounds.size() < 1) {
            // we are done
            return icon;
        } else {
            Random r = new Random();
            int backImageInd = r.nextInt(mIconBackgrounds.size());
            Bitmap background = mIconBackgrounds.get(backImageInd);
            if (background == null) {
                Log.d(TAG, "Can't load background image: " + mIconBackgrounds.get(backImageInd));
                return icon;
            }

            int w = background.getWidth(), h = background.getHeight();
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas tempCanvas = new Canvas(result);

            // draw the background first
            tempCanvas.drawBitmap(background, 0, 0, null);

            // create a mutable mask bitmap with the same mask
            if (icon.getWidth() > w || icon.getHeight() > h) {
                mIconScaleFactor = (mIconScaleFactor == 0) ? 1 : mIconScaleFactor;
                icon = Bitmap.createScaledBitmap(icon, (int) (w * mIconScaleFactor), (int) (h * mIconScaleFactor), false);
            }

            if (mIconMask != null) {
                renderIconBackground(icon, mIconMask, tempCanvas, w, h, true);
            } else {
                renderIconBackground(icon, background, tempCanvas, w, h, false);
            }

            // paint the front
            if (mIconFront != null) {
                tempCanvas.drawBitmap(mIconFront, 0, 0, null);
            }

            return result;
        }
    }

    private void renderIconBackground(Bitmap icon, Bitmap maskImage, Canvas tempCanvas, int w, int h, boolean drawOver) {
        // draw the scaled bitmap with mask
        Bitmap mutableMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        Canvas maskCanvas = new Canvas(mutableMask);
        maskCanvas.drawBitmap(maskImage, 0, 0, new Paint());

        // paint the bitmap with mask into the result
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setXfermode(new PorterDuffXfermode(drawOver ? DST_OUT : DST_IN));
        tempCanvas.drawBitmap(icon, (w - icon.getWidth()) / 2, (h - icon.getHeight()) / 2, null);
        tempCanvas.drawBitmap(mutableMask, 0, 0, paint);
        paint.setXfermode(null);
    }

    /**
     * Gets an entry for the package, which can be used as a fallback entry for various components.
     * This method is not thread safe, it must be called from a synchronized method.
     */
    private CacheEntry getEntryForPackage(String packageName, UserHandleCompat user) {
        ComponentName cn = new ComponentName(packageName, EMPTY_CLASS_NAME);
        CacheKey cacheKey = new CacheKey(cn, user);
        CacheEntry entry = mCache.get(cacheKey);
        if (entry == null || entry.icon == null) {
            entry = new CacheEntry();

            try {
                ApplicationInfo info = mPackageManager.getApplicationInfo(packageName, 0);
                entry.title = info.loadLabel(mPackageManager);
                entry.contentDescription = info.loadDescription(mPackageManager);
                Drawable defaultDrawable = info.loadIcon(mPackageManager);
                if (!TextUtils.isEmpty(mCurrentIconTheme)) {
                    entry.icon = createNewIconBitmap(null, packageName, null, defaultDrawable);
                }

                if (entry.icon == null) {
                    // pick default icon
                    Log.d(TAG, packageName + " icon NOT FOUND in theme = " + mCurrentIconTheme);
                    entry.icon = Utilities.createIconBitmap(defaultDrawable, mContext);
                }
                mCache.put(cacheKey, entry);
            } catch (NameNotFoundException e) {
                if (DEBUG) Log.d(TAG, "Application not installed " + packageName);
            }
        }
        return entry;
    }
}
