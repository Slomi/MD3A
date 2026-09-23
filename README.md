# MD3A: Android client for Macro Deck 3

`MD3A-MacroDeck3-Client.apk` is ready to install (Android 5.0+, package `app.md3a.client`, installs alongside the old Macro Deck Client).

## Why it's built this way

- The old Macro Deck 2 app ([Macro-Deck-Client-App](https://github.com/Macro-Deck-App/Macro-Deck-Client-App), Ionic/Capacitor) speaks the MD2 WebSocket protocol on port 8191. Macro Deck 3 is a different product: a .NET host with REST + JSON WebSocket on port **8193**. The authors say it outright (`engineering/api/macro-deck-2-app.md`): the MD2 app *cannot speak the Macro Deck 3 UI protocol*. So patching the MD2 APK won't make it work.
- The official Companion App for MD3 hasn't shipped yet ("coming soon" on Google Play). It will be paid.
- But the MD3 host ships a free built-in **web client** (framework-free, served at `/`). This app is a native shell around it, the way the MD2 app is a Capacitor shell around its web part. The icons are taken from the MD2 app (MIT).

The app doesn't touch the Companion licensing at all. It uses the same web client you get by opening `http://<PC-IP>:8193` in a phone browser.

## What the app does

- **Network discovery**: DNS-SD `_macrodeck._tcp` (MD3 advertises itself on the network).
- **Manual entry**: `192.168.1.10`, `192.168.1.10:8193`, `http://pc.local:8193`.
- **QR code from MD3** (Network panel): scan it with the phone's camera or any QR scanner and pick "Macro Deck 3 Client" in "Open with" / "Share". Or copy the link and tap "Paste link from clipboard". The link carries the address and the **pairing code**, so you get in without a password (the code goes to the web client as `#enroll=<code>`).
- The 6-digit pairing code can also be typed by hand (it's shown next to the QR code, valid for 15 minutes).
- The host is verified with `GET /api/auth/status` (the MD3 marker is `setupComplete`). If you point it at an MD2 host, it will say so.
- Full screen, screen stays on, remembers the last host and reconnects automatically, retries on its own when the connection drops. Back button: disconnect / pick a different host.
- English and Russian.

## Building

```bash
bash build.sh
```

No Gradle and no Android SDK license acceptance needed. The toolchain lives in `C:\mdw\tools` (override with `MD3A_TOOLS`):
- JDK 17 (Amazon Corretto)
- `aapt2`, `r8` (d8), `apksig` from Google Maven (Apache 2.0)
- `android.jar` API 34 from AOSP `prebuilts/sdk` (Apache 2.0)

The signing key is `keystore/md3a.p12` (password `md3a-android`). **Keep it**: updates only install over the existing app when signed with the same key.

## Structure

```
app/AndroidManifest.xml
app/res/                       strings (en/ru), theme, MD2 icons
app/src/app/md3a/client/
  MainActivity.java            host selection: discovery, manual input, QR/connect link, verification
  DeckActivity.java            full-screen WebView with the MD3 web client
  ConnectLink.java             connect link reader (spec: engineering/api/connect-link.md, checked against the conformance vector)
tools/                         APK signer, packaging, compile-time stub
```
