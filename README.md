LeanLauncher
============

Minimal, dumb launcher forked from AOSP's [Launcher 3](https://android.googlesource.com/platform/packages/apps/Launcher3/). It basically removes 'fluffy' features and clutter from Launcher 3, adds a few enhancements/
The idea is to make a launcher similar in experience to older versions of [Smart Launcher](https://play.google.com/store/apps/details?id=ginlemon.flowerfree).

Why
---
Because I got bored on a weekend and frustrated with launchers adding more crap I have no use far. Besides, everyone writes a launcher at somepoint :)

Screenshots
-----------

Changelog
=========

* Migrated Launcher 3 to Android Studio/gradle project setup
* Support for Icon themes
* Support for Swipe gestures
* Removed extraneous permissions
* Removed workspaces. Only one workspace
* Removed Hotseat/"dock"
* Removed folder support
* Removed built-in wallpaper manager. You can still set wallpapers using an external app like G!Photos 
* Removed Backup settings to SD card. There is not much to backup anyway
* Removed migration from old launcher settings
* Removed welcome/first-run setup wizard
* Removed OEM support for launcher customisation
* Removed hard-coded support for Quick search bar(Google search)
* Apps will not be added to launcher on install, because of limited workspace
* Basic performance optimizations and code cleanup from running Android lint
* Cleaned up project resources

Credits
=======

Forked from Launcher3 at https://android.googlesource.com/platform/packages/apps/Launcher3
Launcher icon from Material icons project

License
=======

See LICENSE

Todo
====

- [x] fix: bottom space for remove 
- [x] fix: widget overview mode not exiting
- [ ] Add support for icon theme
- [ ] swipe to all apps, recent tasks
- [ ] run del script
- [ ] final proguard cfg
- [ ] commit, publish
- [ ] Use a less complicated backend
- [ ] Add Preferences: import/export settings, all apps button behaviour, icon theme, layout cells?
- [ ] better landscape support
- [ ] Simple search bar for contacts/app
