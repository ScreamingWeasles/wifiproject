# Screaming Weasels v0.8.3 — Dedicated Device Scan tab

- Renamed Scan tab to **Wi-Fi Scan**. Nearby SSIDs, Join, sorting and channel graphs remain there.
- Added **Device Scan** tab containing the existing LAN discovery and UDP discovery panel.
- Kept the shared `discoverySession` state so LAN/UDP results are not reset merely by changing tabs.
- Existing Wi-Fi, Survey, Speed, AP Health, Floor Plan tabs retained.

## Build
Open OfficeWifiInspector in Android Studio and Build > Rebuild Project. This source archive has not been Android-SDK compiled in this environment.
