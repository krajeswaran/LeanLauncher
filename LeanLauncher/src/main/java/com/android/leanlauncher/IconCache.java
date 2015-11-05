/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
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

    public static final String GO_LAUNCHER_THEME_NAME = "com.gau.go.launcherex.theme";
    public static final String ADW_LAUNCHER_THEME_NAME = "org.adw.launcher.THEMES";
    public static final String SMART_LAUNCHER_THEME_NAME = "ginlemon.smartlauncher.THEMES";

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

    private String mCurrentIconTheme = null;

    public ArrayMap<String, String> getAvailableIconPacks() {
        ArrayMap<String, String> availableIconPacks = new ArrayMap<>();

        PackageManager pm = mContext.getPackageManager();

        // fetch installed icon packs for popular launchers
        List<ResolveInfo> adwlauncherthemes = pm.queryIntentActivities(new Intent(ADW_LAUNCHER_THEME_NAME), PackageManager.GET_META_DATA);
        List<ResolveInfo> golauncherthemes = pm.queryIntentActivities(new Intent(GO_LAUNCHER_THEME_NAME), PackageManager.GET_META_DATA);
        List<ResolveInfo> smartlauncherthemes = pm.queryIntentActivities(new Intent(SMART_LAUNCHER_THEME_NAME), PackageManager.GET_META_DATA);

        // merge those lists
        List<ResolveInfo> rinfo = new ArrayList<>(adwlauncherthemes);
        rinfo.addAll(golauncherthemes);
        rinfo.addAll(smartlauncherthemes);

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
    public void loadIconPackDrawables() {
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
                } catch (IOException e1) {
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
                        }
                    }
                    eventType = xpp.next();
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Cannot load icon pack" + e);
        } catch (XmlPullParserException e) {
            Log.d(TAG, "Cannot parse icon pack appfilter.xml" + e);
        } catch (IOException e) {
            Log.d(TAG, "Exception loading icon pack " + e);
        }
    }

    public Drawable getFullResDefaultActivityIcon() {
        return getFullResIcon(Resources.getSystem(), android.R.mipmap.sym_def_app_icon);
    }

    private Drawable getFullResIcon(Resources resources, int iconId) {
        Drawable d;
        try {
            d = resources.getDrawableForDensity(iconId, mIconDpi);
        } catch (Resources.NotFoundException e) {
            d = null;
        }

        return (d != null) ? d : getFullResDefaultActivityIcon();
    }

    private Drawable loadDrawableFromIconPack(Resources iconPackRes, String packageName, String drawableName) {
        if (drawableName == null) {
            return null;
        }

        int id = iconPackRes.getIdentifier(drawableName, "drawable", packageName);
        if (id > 0) {
            return iconPackRes.getDrawable(id);
        }
        return null;
    }

    public Drawable getFullResIcon(String packageName, int iconId) {
        if (mCurrentIconTheme != null) {
            // load themed icon
            Drawable icon = findDrawableFromIconPack(packageName, null);

            if (icon != null) {
                Log.d(TAG, packageName + " icon found in theme: " + mCurrentIconTheme);
                return icon;
            }
        }

        // continue loading default icon
        Resources resources;
        try {
            resources = mPackageManager.getResourcesForApplication(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            resources = null;
        }
        if (resources != null) {
            if (iconId != 0) {
                return getFullResIcon(resources, iconId);
            }
        }
        return getFullResDefaultActivityIcon();
    }

    public int getFullResIconDpi() {
        return mIconDpi;
    }

    public Drawable getFullResIcon(ActivityInfo info) {
        return getFullResIcon(info.applicationInfo.packageName, info.getIconResource());
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

    public synchronized Bitmap getIcon(Intent intent, UserHandleCompat user) {
        ComponentName component = intent.getComponent();
        // null info means not installed, but if we have a component from the intent then
        // we should still look in the cache for restored app icons.
        if (component == null) {
            return getDefaultIcon(user);
        }

        LauncherActivityInfoCompat launcherActInfo = mLauncherApps.resolveActivity(intent, user);
        CacheEntry entry = cacheLocked(component, launcherActInfo, null, user, true);
        return entry.icon;
    }

    public synchronized Bitmap getDefaultIcon(UserHandleCompat user) {
        if (!mDefaultIcons.containsKey(user)) {
            mDefaultIcons.put(user, makeDefaultIcon(user));
        }
        return mDefaultIcons.get(user);
    }

    public synchronized Bitmap getIcon(ComponentName component, LauncherActivityInfoCompat info,
            ArrayMap<Object, CharSequence> labelCache) {
        if (info == null || component == null) {
            return null;
        }

        CacheEntry entry = cacheLocked(component, info, labelCache, info.getUser(), false);
        return entry.icon;
    }

    public boolean isDefaultIcon(Bitmap icon, UserHandleCompat user) {
        return mDefaultIcons.get(user) == icon;
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
                if (mCurrentIconTheme != null) {
                    entry.icon = findBitmapFromIconPack(info.getComponentName().getPackageName(), componentName.getClassName());
                }

                if (entry.icon == null) {
                    // pick default icon
                    Log.d(TAG, labelKey + " icon NOT FOUND in theme: " + mCurrentIconTheme);
                    entry.icon = Utilities.createIconBitmap(
                            info.getBadgedIcon(mIconDpi), mContext);
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
                    entry.icon = getDefaultIcon(user);
                }
            }
        }
        return entry;
    }

    private Bitmap findBitmapFromIconPack(String packageName, String className) {
        Drawable drawable = findDrawableFromIconPack(packageName, className);

        if (drawable == null) {
            return null;
        } else {
            return Utilities.createIconBitmap(drawable, mContext);
        }
    }

    private Drawable findDrawableFromIconPack(String packageName, String className) {
        try {
            String drawableName = null;

            for (CacheKey key : mCache.keySet()) {
                if (key != null && key.componentName.getPackageName().equals(packageName)) {
                    CharSequence cacheDrawableName = mCache.get(key).drawableName;
                    if (cacheDrawableName != null) {
                        drawableName = cacheDrawableName.toString();
                        Log.d(TAG, drawableName + " -- found for -- " + packageName);

                        if (className == null || className.equals(key.componentName.getClassName())) {
                            // we've found it
                            break;
                        }
                    }
                }
            }

            return loadDrawableFromIconPack(
                    mPackageManager.getResourcesForApplication(mCurrentIconTheme),
                    mCurrentIconTheme,
                    drawableName);
        } catch (NameNotFoundException e) {
            Log.d(TAG, packageName + " icon not found in current icon pack " + mCurrentIconTheme);
            return null;
        }
    }

    /**
     * Adds a default package entry in the cache. This entry is not persisted and will be removed
     * when the cache is flushed.
     */
    public synchronized void cachePackageInstallInfo(String packageName, UserHandleCompat user,
            Bitmap icon, CharSequence title) {
        remove(packageName, user);

        CacheEntry entry = getEntryForPackage(packageName, user);
        if (!TextUtils.isEmpty(title)) {
            entry.title = title;
        }
        if (icon != null) {
            entry.icon = Utilities.createIconBitmap(icon, mContext);
        }
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
                if (mCurrentIconTheme != null) {
                    entry.icon = findBitmapFromIconPack(packageName, null);
                }

                if (entry.icon == null) {
                    // pick default icon
                    Log.d(TAG, packageName + " icon NOT FOUND in theme = " + mCurrentIconTheme);
                    entry.icon = Utilities.createIconBitmap(info.loadIcon(mPackageManager), mContext);
                }
                mCache.put(cacheKey, entry);
            } catch (NameNotFoundException e) {
                if (DEBUG) Log.d(TAG, "Application not installed " + packageName);
            }
        }
        return entry;
    }
}
