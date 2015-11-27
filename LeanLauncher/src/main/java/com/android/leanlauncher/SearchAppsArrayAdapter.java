/**
 *   Copyright (c) 2015. Kumaresan Rajeswaran
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

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.leanlauncher.compat.UserHandleCompat;

import java.util.ArrayList;
import java.util.TreeMap;


public class SearchAppsArrayAdapter extends ArrayAdapter<AppInfo> {
    Filter mAppNameFilter = new Filter() {
        @Override
        public CharSequence convertResultToString(Object resultValue) {
            return ((AppInfo) resultValue).title;
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();

            TreeMap<Integer, AppInfo> suggestions = new TreeMap<>();
            if (!TextUtils.isEmpty(constraint)) {
                String constraintStr = constraint.toString().toLowerCase();

                int startsWithRank = 0, containsRank = mApps.size();
                for (AppInfo app : mApps) {
                    String appTitle = app.title.toString().toLowerCase();
                    if (!TextUtils.isEmpty(appTitle)) {
                        if (appTitle.startsWith(constraintStr)) {
                            startsWithRank++;
                            suggestions.put(startsWithRank, app);
                        } else if (appTitle.contains(constraintStr)) {
                            containsRank++;
                            suggestions.put(containsRank, app);
                        }
                    }
                }

                ArrayList<AppInfo> apps = new ArrayList<>();
                for (AppInfo app: suggestions.values()) {
                    apps.add(app);
                }
                results.values = apps;
                results.count = apps.size();
            }
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            clear();
            if (results != null && results.count > 0) {
                addAll((ArrayList<AppInfo>) results.values);
                notifyDataSetChanged();
            } else {
                notifyDataSetInvalidated();
            }
        }
    };

    public ArrayList<AppInfo> mApps;

    public SearchAppsArrayAdapter(Context context, ArrayList<AppInfo> apps) {
        super(context, R.layout.search_apps_item);
        this.mApps = apps;
    }

    private static class ViewHolder {
        TextView appTitle;
        ImageView appIcon;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final AppInfo app = getItem(position);

        ViewHolder viewHolder;
        if (convertView == null) {
            convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.search_apps_item, parent, false);

            viewHolder = new ViewHolder();
            viewHolder.appTitle = (TextView) convertView.findViewById(R.id.tv_search_result_app_title);
            viewHolder.appIcon = (ImageView) convertView.findViewById(R.id.iv_search_result_app_icon);
            convertView.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        viewHolder.appTitle.setText(app.title);

        Drawable icon = new BitmapDrawable(getContext().getResources(),
                LauncherAppState.getInstance().getIconCache().getIcon(app.intent, UserHandleCompat.myUserHandle()));
        viewHolder.appIcon.setImageDrawable(icon);

        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchAppActivity(app.intent);
            }
        });
        convertView.setClickable(true);

        return convertView;
    }

    private void launchAppActivity(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (SecurityException e) {
            Toast.makeText(getContext(), R.string.activity_not_available, Toast.LENGTH_SHORT).show();
            Log.e("SearchWidget", "Widget does not have the permission to launch: " + intent + e);
        }
    }

    @Override
    public Filter getFilter() {
        return mAppNameFilter;
    }
}
