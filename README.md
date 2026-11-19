# esTV Custom Launcher

Android TV launcher (`com.excel.estvlauncher.custom`) for hotels with an **estv-custom-launcher-cms** on the LAN.

- Home screen: wallpaper from the CMS, **Menu** (opens the Secret Menu) and the app buttons set on the CMS.
- Checks in with the CMS every 5 minutes (`docs/box-protocol.md` in estv-custom-launcher-cms): reports MAC, IP,
  box name and versions; applies wallpaper, buttons and APK updates (silent install with root).
- If the CMS cannot be reached, asks eslink for the CMS address (app_id + secret + property ID from the CMS).
- A new box uses CMS **172.19.0.3:4053**; change it in Secret Menu > CMS IP / Port.

## Build

1. Copy `secrets.properties.example` to `secrets.properties` and fill in the eslink app (role "esTV Custom Launcher").
2. `app/keystore.properties` points at the esTV keystore (same as esTVLauncher). Both files are git-ignored.
3. `gradlew assembleRelease` (JDK 17).
