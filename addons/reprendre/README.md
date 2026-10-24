# Reprendre

The app behind the launcher's `continue_row` (a "Continue watching" row above the dock). It reads the Watch Next
rows that Prime Video, Disney+, Canal+ and Jellyfin publish, plus Netflix's `continueWatching` preview rows, sorts them by
last playback (Netflix publishes no date, so its rows come last), and hands them to the launcher through the content
provider `tv.reprendre.items`. It downloads and caches the posters itself, so the launcher keeps no network access.
Opened on its own it shows the same row full screen.

Permissions: `READ_TV_LISTINGS` (granted with `pm grant` by `build.sh`) and `INTERNET` (posters). The provider serves only
poster addresses from its latest answer. Without this app the launcher shows no row.

Build and install: `TV_HOST=192.168.1.50 addons/reprendre/build.sh`, then turn on `continue_row` in
`launcher/app/src/main/res/values/config.xml` and rebuild the launcher. To follow other apps, edit `Items.java`.
