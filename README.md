# Linkdroid

Local-only screen mirroring using embedded scrcpy. No cloud, no internet required after setup.

## Quick Start

1. Enable **Wireless debugging** on your phone: Settings → Developer options → Wireless debugging → ON
2. (Optional) Enable **USB debugging**: Settings → Developer options → USB debugging → ON, then connect via USB cable
3. Install and run the **Linkdroid Signalizer** app on your phone (for wireless mirroring)
4. Double-click **`Start Mirroring.bat`**
5. Choose how you want to connect:
   - **Use Linkdroid App** — auto-discovers your phone via Tailscale CLI, mDNS, or TCP beacon
   - **Enter IP:port manually** — for direct connection
   - **USB debugging** — connect via USB cable
6. scrcpy opens and the launcher monitors the connection

## How it works

### Discovery (launcher side)
The launcher uses multiple discovery methods in parallel to find your phone:

1. **Tailscale CLI** — queries `tailscale status --json` for all tailnet device IPs
2. **TCP beacon** — probes discovered IPs on port `9876` for the Linkdroid beacon
3. **mDNS** — listens on all network adapters for `_linkdroid._tcp.local.` services
4. **Saved IPs** — falls back to `devices.json` and `settings.json`

If the phone is on Tailscale, the launcher discovers it via the Tailscale CLI. If on the same LAN, mDNS finds it. If neither works, saved IPs or manual entry are used.

### Android app side
The **Linkdroid Signalizer** app:
- Advertises a unique mDNS service (`_linkdroid._tcp.local.`) with device-specific name
- Serves the ADB wireless-debugging port over a TCP beacon on port `9876`
- Includes Wi-Fi IP, Tailscale IP, Android version, and device model in the beacon
- Works over local Wi-Fi, Ethernet, and Tailscale

### Managed session
The launcher stays resident and manages the entire mirroring session:
- Monitors ADB connectivity via heartbeat
- Automatically reconnects if the connection drops (up to 5 attempts, 10s each)
- Scans all network adapters on each reconnection attempt
- Provides stop control from the terminal window

## Manual commands (if needed)

```powershell
.\scrcpy\adb.exe pair <IP>:<PORT>
.\scrcpy\adb.exe connect <IP>:<PORT>
.\scrcpy\scrcpy.exe --max-fps 60 --max-size 720 --video-bit-rate 8M --turn-screen-off
```

## Requirements

- Portable JDK bundled in `.\jdk\` (no system Java needed)
- scrcpy embedded in `.\scrcpy\`
- **Tailscale must be installed and running on both PC and phone** for Tailscale discovery
- Phone and PC on the same network or same Tailscale tailnet

## Discovery notes

- **Tailscale CLI**: requires `tailscale` command in PATH or installed at `C:\Program Files\Tailscale\tailscale.exe`. The phone must have the Tailscale app installed and running.
- **mDNS**: listens on all network adapters for `_linkdroid._tcp.local.` services. May not work across Tailscale's DERP mesh.
- **Local network scan**: if Tailscale CLI finds no devices, the launcher falls back to scanning all local network interfaces for Tailscale IPs (100.x.x.x range) and probing them with the TCP beacon.
- **TCP beacon**: the phone serves a beacon on port `9876` with its ADB port, WiFi IP, and Tailscale IP. The launcher probes all discovered IPs for this beacon.

## Files

- `Start Mirroring.bat` — launches the Java launcher
- `LinkdroidLauncher.java` — Java launcher with managed session
- `settings.json` — optional Tailscale IP configuration (manual override)
- `devices.json` — saved device IPs
- `linkdroid.log` — launcher log file
- `android-app/linkdroid-signalizer/` — Android companion app source
- `build/` — compiled launcher classes

## Android App Setup

1. Open `android-app/linkdroid-signalizer/` in Android Studio
2. Build and install the app on your phone
3. Enable **Wireless debugging** on your phone
4. Open the app and tap **Start Signalizer**
5. Grant location permission when prompted
6. Keep the app open and the notification visible

If the ADB port is not detected automatically, tap the notification to open the config screen and enter it manually.

## Security

- The TCP beacon on port `9876` is unauthenticated and intended for local-network use only
- Tailscale limits beacon exposure to devices in your tailnet
- The pairing code feature (planned) will add authentication before connecting
- ADB over TCP traffic is encrypted by Android's ADB implementation

## Notes

- All data stays on your local network or Tailscale tailnet
- scrcpy is embedded in `.\scrcpy\` — no separate installation needed
- The launcher automatically reconnects if the phone moves between networks
- `--turn-screen-off` keeps the phone display off while mirroring
- Choose from 720p (default), 1080p, or 4K resolution before connecting
- All runtime files are bundled in the project — no PATH or environment variables required
