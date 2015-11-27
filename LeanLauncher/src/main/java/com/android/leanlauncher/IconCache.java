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
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.UserHandle;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;

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

    public String getCurrentIconTheme() {
        return mCurrentIconTheme;
    }

    public void setCurrentIconTheme(String iconTheme) {
        mCurrentIconTheme = iconTheme;
    }

    private static class CacheEntry {
        public Bitmap icon;
        public CharSequence title;
        public CharSequence contentDescription;
        public CharSequence drawableName;
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

    private final ArrayMap<UserHandleCompat, Bitmap> mDefaultIcons =
            new ArrayMap<>();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManagerCompat mUserManager;
    private final LauncherAppsCompat mLauncherApps;
    private final ArrayMap<CacheKey, CacheEntry> mCache =
            new ArrayMap<>(INITIAL_ICON_CACHE_CAPACITY);
    private int mIconDpi;
    private String mCurrentIconTheme = null;
    private ArrayList<String> mIconBackgroundNames = new ArrayList<>();
    private String mIconMaskName;
    private String mIconFrontName;
    private float mIconScaleFactor;

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
                getString(context.getString(R.string.pref_icon_theme_key), null);
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


    // should be called in background, not thread-safe
    // gracelessly stolen from http://stackoverflow.com/a/31512017
    public void loadIconPackDrawables() {
        final long t = SystemClock.uptimeMillis();
        if (mCurrentIconTheme == null
                || mContext.getResources().getString(R.string.pref_no_icon_theme).equals(mCurrentIconTheme)) {
            return;
        }

        // load appfilter.xml from the icon pack package
        try {
            XmlPullParser xpp = null;

            Resources iconPackres = mPackageManager.getResourcesForApplication(mCurrentIconTheme);
            int appfilterid = iconPackres.getIdentifier("appfilter", "xml", mCurrentIconTheme);
            if (appfilterid > 0) {
                xpp = iconPackres.getXml(appfilterid);
            } else {
                // no resource found, try to open it from assests folder
                try {
                    InputStream appfilterstream = iconPackres.getAssets().open("appfilter.xml");

                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    xpp = factory.newPullParser();
                    xpp.setInput(appfilterstream, "utf-8");
                } catch (IOException e) {
                    Log.d(TAG, "No appfilter.xml file");
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

                            CacheEntry entry = new CacheEntry();
                            entry.drawableName = drawableName;

                            try {
                                componentName = componentName.substring(componentName.indexOf('{') + 1, componentName.indexOf('}'));
                            } catch (StringIndexOutOfBoundsException e) {
                                Log.d(TAG, "Can't parse icon for package = " + componentName);
                                eventType = xpp.next();
                                continue;
                            }

                            ComponentName componentNameKey = ComponentName.unflattenFromString(componentName);
                            if (componentNameKey != null) {
                                CacheKey key = new CacheKey(componentNameKey, UserHandleCompat.myUserHandle());
                                mCache.put(key, entry);
                            } else {
                                Log.d(TAG, "ComponentName can't be obtained from: " + componentName);
                            }
                        } else if ("iconback".equals(xpp.getName())) {
                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                if (xpp.getAttributeName(i).startsWith("img")) {
                                    mIconBackgroundNames.add(xpp.getAttributeValue(i));
                                }
                            }
                        } else if ("iconmask".equals(xpp.getName())) {
                            if (xpp.getAttributeCount() > 0 && "img1".equals(xpp.getAttributeName(0))) {
                                mIconMaskName = xpp.getAttributeValue(0);
                            }
                        } else if ("iconupon".equals(xpp.getName())) {
                            if (xpp.getAttributeCount() > 0 && "img1".equals(xpp.getAttributeName(0))) {
                                mIconFrontName = xpp.getAttributeValue(0);
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
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Cannot load icon pack" + e);
        } catch (XmlPullParserException e) {
            Log.d(TAG, "Cannot parse icon pack appfilter.xml" + e);
        } catch (IOException e) {
            Log.d(TAG, "Exception loading icon pack " + e);
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
    public synchronized void getTitleAndIcon(AppInfo application, LauncherActivityInfoCompat info,
            ArrayMap<Object, CharSequence> labelCache) {
        CacheEntry entry = cacheLocked(application.componentName, info, labelCache,
                info.getUser(), false);

        application.title = entry.title;
        application.contentDescription = entry.contentDescription;
    }

    public Bitmap getIconForComponent(ComponentName componentName, UserHandle profile) {
        if (componentName == null || TextUtils.isEmpty(componentName.getPackageName())) {
            // happens on first load sometimes
            return null;
        }
        CacheEntry entry = getEntryForPackage(componentName.getPackageName(), UserHandleCompat.fromUser(profile));
        return entry.icon;
    }

    public synchronized Bitmap getIcon(Intent intent, UserHandleCompat user) {
        ComponentName component = intent.getComponent();
        // null info means not installed, but if we have a component from the intent then
        // we should still look in the cache for restored app icons.
        if (component == null) {
            return getDefaultUserIcon(user);
        }

        LauncherActivityInfoCompat launcherActInfo = mLauncherApps.resolveActivity(intent, user);
        CacheEntry entry = cacheLocked(component, launcherActInfo, null, user, true);
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
    private CacheEntry cacheLocked(ComponentName componentName, LauncherActivityInfoCompat info,
            ArrayMap<Object, CharSequence> labelCache, UserHandleCompat user, boolean usePackageIcon) {
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
                if (mCurrentIconTheme != null) {
                    entry.icon = createNewIconBitmap(componentName.getPackageName(), componentName.getClassName(),
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
                    CacheEntry packageEntry = getEntryForPackage(
                            componentName.getPackageName(), user);
                    if (packageEntry != null) {
                        if (DEBUG) Log.d(TAG, "using package default icon for " +
                                componentName.toShortString());
                        entry.icon = packageEntry.icon;
                        entry.title = packageEntry.title;
                    }
                }
                if (entry.icon == null) {
                    if (DEBUG) Log.d(TAG, "using default icon for " +
                            componentName.toShortString());
                    entry.icon = getDefaultUserIcon(user);
                }
            }
        }
        return entry;
    }

    private Bitmap createNewIconBitmap(String packageName, String className, Drawable defaultDrawable) {
        String drawableName = findDrawableFromIconPack(packageName, className);

        return createIconBitmapFromTheme(drawableName, defaultDrawable);
    }

    private String findDrawableFromIconPack(String packageName, String className) {
        String drawableName = null;

        for (CacheKey key : mCache.keySet()) {
            if (key != null && key.componentName.getPackageName().equalsIgnoreCase(packageName)) {
                CharSequence cacheDrawableName = mCache.get(key).drawableName;
                if (cacheDrawableName != null) {
                    drawableName = cacheDrawableName.toString();
                    Log.d(TAG, drawableName + " -- found for -- " + packageName);

                    if (className == null || className.equalsIgnoreCase(key.componentName.getClassName())) {
                        // we've found it
                        break;
                    }
                }
            }
        }

        return drawableName;
    }

    private Drawable loadDrawableFromIconPack(Resources iconPackRes, String packageName, String drawableName) {
        if (TextUtils.isEmpty(drawableName)) {
            return null;
        }

        int id = iconPackRes.getIdentifier(drawableName, "drawable", packageName);
        if (id > 0) {
            return iconPackRes.getDrawable(id);
        }
        return null;
    }

    private Bitmap loadBitmapFromIconPack(Resources iconPackRes, String packageName, String drawableName) {
        Drawable drawable = loadDrawableFromIconPack(iconPackRes, packageName, drawableName);

        if (drawable != null) {
            return Utilities.createIconBitmap(drawable, mContext);
        }
        return null;
    }

    public Bitmap createIconBitmapFromTheme(String iconDrawableName, Drawable defaultDrawable) {
        Bitmap icon; Resources iconPackRes;
        try {
            iconPackRes = mContext.getPackageManager().getResourcesForApplication(mCurrentIconTheme);
            icon = loadBitmapFromIconPack(iconPackRes, mCurrentIconTheme, iconDrawableName);
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Can't find icon theme: " + mCurrentIconTheme + " for " + iconDrawableName);
            return null;
        }

        if (icon == null) {
            Log.d(TAG, "Using default icon, can't find icon drawable: " + iconDrawableName + " in " + mCurrentIconTheme);
            icon = Utilities.createIconBitmap(defaultDrawable, mContext);
        }


        if (mIconBackgroundNames.size() < 1) {
            // we are done
            return icon;
        } else {
            // maybe find bg by icon hue? don't like random bg
            int backImageInd = Math.round(mIconBackgroundNames.size() / 2);
            Bitmap background = loadBitmapFromIconPack(iconPackRes, mCurrentIconTheme, mIconBackgroundNames.get(backImageInd));
            if (background == null) {
                Log.d(TAG, "Can't load background image: " + mIconBackgroundNames.get(backImageInd));
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

            Bitmap maskImage = loadBitmapFromIconPack(iconPackRes, mCurrentIconTheme, mIconMaskName);
            if (maskImage != null) {
                renderIconBackground(icon, maskImage, tempCanvas, w, h);
            } else {
                renderIconBackground(icon, background, tempCanvas, w, h);
            }

            // paint the front
            Bitmap frontImage = loadBitmapFromIconPack(iconPackRes, mCurrentIconTheme, mIconFrontName);
            if (frontImage != null) {
                tempCanvas.drawBitmap(frontImage, 0, 0, null);
            }

            // clean up canvas
            tempCanvas.setBitmap(null);

            return result;
        }
    }

    private void renderIconBackground(Bitmap icon, Bitmap maskImage, Canvas tempCanvas, int w, int h) {
        // draw the scaled bitmap with mask
        Bitmap mutableMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        Canvas maskCanvas = new Canvas(mutableMask);
        maskCanvas.drawBitmap(maskImage, 0, 0, new Paint());

        // paint the bitmap with mask into the result
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
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
                Drawable defaultDrawable = info.loadIcon(mPackageManager);
                if (mCurrentIconTheme != null) {
                    entry.icon = createNewIconBitmap(packageName, null, defaultDrawable);
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
