package com.android.leanlauncher;

/**
 * Central list of files the Launcher writes to the application data directory.
 *
 * To add a new Launcher file, create a String constant referring to the filename, and add it to
 * ALL_FILES, as shown below.
 */
public class LauncherFiles {
    public static final String LAUNCHER_DB = "launcher.db";
    public static final String LAUNCHER_PREFERENCES = "launcher.preferences";
    public static final String SHARED_PREFERENCES_KEY = "com.android.leanlauncher.prefs";
    public static final String WIDGET_PREVIEWS_DB = "widgetpreviews.db";
}
