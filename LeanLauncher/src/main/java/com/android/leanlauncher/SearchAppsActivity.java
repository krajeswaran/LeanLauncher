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

import android.app.Activity;
import android.os.Bundle;

import com.android.leanlauncher.compat.LauncherActivityInfoCompat;
import com.android.leanlauncher.compat.LauncherAppsCompat;
import com.android.leanlauncher.compat.UserHandleCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by krajeswa on 11/15/15.
 */
public class SearchAppsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.search_apps);

        SearchAppsArrayAdapter adapter = new SearchAppsArrayAdapter(this,
                LauncherAppState.getInstance().getModel().getAllApps());

        SearchAutoCompleteTextView searchBar = (SearchAutoCompleteTextView) findViewById(R.id.et_search_apps);
        searchBar.setSearchActivity(this);
        searchBar.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Utilities.showStatusbar(getWindow(), this);
    }
}
