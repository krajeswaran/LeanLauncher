LeanLauncher
============

Minimal, dumb launcher forked from AOSP's [launcher 3](https://android.googlesource.com/platform/packages/apps/Launcher3/). It basically removes features and clutter from Launcher 3,
similar in experience to [Smart Launcher](https://play.google.com/store/apps/details?id=ginlemon.flowerfree).

Why
---
Because I got bored on a weekend and frustrated with launchers adding more crap I have no use far. Besides, everyone writes a launcher at somepoint :)

Screenshots
-----------

Changelog
=========

* Migrated Launcher 3 to Android Studio/gradle project setup
* No workspaces. Only one workspace
* No Hotseat/"dock"
* No folder support
* No built-in wallpaper manager. You can pick wallpapers, but if you want to crop/adjust wallpapers you need an external app like G!Photos installed
* No Backup settings to SD card. There is not much to backup anyway
* No migration from old launcher settings
* No welcome/first-run setup wizard
* Apps will not be added to launcher on install, because of limited workspace
* Basic performance optimizations from running Android lint
* Cleaned up layouts, translations

Credits
=======

Forked from Launcher3 at https://android.googlesource.com/platform/packages/apps/Launcher3
Launcher icon from Material icons project

License
=======

See LICENSE

Todo
====

-[] layout adjustments:  widget overview mode not exiting
-[] swipe to all apps, recent tasks
-[] add monkey test
-[] lint fixes
-[] commit, publish
-[] Add support for icon theme
-[] Use a less complicated backend
-[] Add Preferences: import/export settings, all apps button behaviour, icon theme, layout cells?
