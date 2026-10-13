# Screaming Weasels v0.8.1 — Persist LAN results

- LAN device and UDP discovery state is owned by MainActivity, not by the Scan tab composable. Switching tabs does not discard results or cancel an active scan.
- LAN results and UDP observations are saved to private app SharedPreferences and restored on a fresh app launch. Saves occur as responsive hosts are found, periodically during scanning, and at completion.
- Clear saved results explicitly using the new button (disabled while a scan is running).
- This update specifically addresses LAN/UDP discovery. Other tabs retain their existing state behavior; survey/Speed/AP Health persistence across force-stop or reinstall is not yet implemented.
- No Android SDK/Gradle compiler is installed in this environment, so the source has not been compiled. Open in Android Studio and Build > Rebuild Project.
