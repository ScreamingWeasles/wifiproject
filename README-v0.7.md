# Screaming Weasels Wi-Fi Teaser v0.7 — Floor Plan Preview

This project is based on the uploaded v0.6 Stage A source. The existing Wi-Fi, Scan, Survey, Speed Test and AP Health screens are preserved.

## New Floor Plan tab
- Import PNG, JPG, or first page of a PDF using Android's document picker.
- Start a blank drawing and draw wall segments by dragging in Wall mode.
- Tap in Measure mode to record current connected BSSID/RSSI at a manually chosen location.
- Place AP markers and text labels.
- Save and load one plan locally (image, walls, and markers).
- Export a one-page PDF with the plan and up to 19 readings.

## Current limitations
- This is a first implementation and has not been compiled or device-tested in this environment.
- The colored circles represent individual measurement points, **not a scientifically interpolated heatmap**.
- Floor-plan measurements are independent of the existing Survey tab; no automatic linkage to roaming/probe logs yet.
- One saved floor plan per app installation; new saves overwrite the previous plan.
- PDF import uses only the first page. PDF report is a one-page summary.
- Screen touch coordinates are relative to the displayed map; use manual placement. No indoor location tracking.
- Gradle wrapper executable/JAR files are not included in this source ZIP, just as in the supplied v0.6 archive. Copy the working wrapper files from your existing project if needed.
