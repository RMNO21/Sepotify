# Sepotify Troubleshooting & Diagnostics

## 1. Background Playback Termination
- **Cause**: Aggressive battery savers (e.g., Xiaomi MIUI, Samsung OneUI) terminating background processes.
- **Remedy**: Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission and verify Foreground Service notification persistence.

## 2. Audio Focus Glitches
- **Cause**: Transient audio focus loss during navigation alerts or incoming VoIP calls.
- **Remedy**: Configure `AudioAttributes.USAGE_MEDIA` with ducking behavior.

## 3. Bluetooth Audio Latency
- **Cause**: High-latency A2DP profile buffer negotiation.
- **Remedy**: Enable low-latency buffer modes on Android 10+ devices.
