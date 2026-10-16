# Linkdroid Signalizer

Small Android companion app that makes Linkdroid Launcher discover your phone automatically over Wi-Fi or Tailscale.

## What it does
- Advertises a Linkdroid service via mDNS (`_linkdroid._tcp.local.`)
- Serves the ADB wireless-debugging port over a TCP beacon on port `9876`
- Includes both Wi-Fi IP and Tailscale IP so the launcher can connect regardless of which virtual network you are on

## Requirements
- Android 8.0+ (API 26)
- Wireless debugging enabled on the phone
- Phone and PC on the same Tailscale network or local Wi-Fi

## Build
Open `android-app/linkdroid-signalizer` in Android Studio and build the app.

## Usage
1. Install the app on your phone
2. Open the app and tap **Start Signalizer**
3. Keep the notification visible
4. Run **Start Mirroring.bat** on the PC
5. Choose **Use Linkdroid App**
6. The launcher will discover the phone and connect
