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

package com.android.leanlauncher.compat;

import android.annotation.TargetApi;
import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.widget.Toast;

import com.android.leanlauncher.IconCache;
import com.android.leanlauncher.R;

import java.util.ArrayList;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class AppWidgetManagerCompatVL extends AppWidgetManagerCompat {

    private final UserManager mUserManager;
    private final PackageManager mPm;

    AppWidgetManagerCompatVL(Context context) {
        super(context);
        mPm = context.getPackageManager();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Override
    public List<AppWidgetProviderInfo> getAllProviders() {
        ArrayList<AppWidgetProviderInfo> providers = new ArrayList<AppWidgetProviderInfo>();
        for (UserHandle user : mUserManager.getUserProfiles()) {
            providers.addAll(mAppWidgetManager.getInstalledProvidersForProfile(user));
        }
        return providers;
    }

    @Override
    public String loadLabel(AppWidgetProviderInfo info) {
        return info.loadLabel(mPm);
    }

    @Override
    public boolean bindAppWidgetIdIfAllowed(int appWidgetId, AppWidgetProviderInfo info,
            Bundle options) {
        return mAppWidgetManager.bindAppWidgetIdIfAllowed(
                appWidgetId, info.getProfile(), info.provider, options);
    }

    @Override
    public UserHandleCompat getUser(AppWidgetProviderInfo info) {
        return UserHandleCompat.fromUser(info.getProfile());
    }

    @Override
    public void startConfigActivity(AppWidgetProviderInfo info, int widgetId, Activity activity,
            AppWidgetHost host, int requestCode) {
        try {
            host.startAppWidgetConfigureActivityForResult(activity, widgetId, 0, requestCode, null);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public Drawable loadPreview(AppWidgetProviderInfo info) {
        return info.loadPreviewImage(mContext, 0);
    }
}
