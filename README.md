# Screaming Weasels Wi-Fi Teaser — v0.6 Stage A

**Make Your Wi-Fi Squeal.**

Built from the existing v0.5 source. Retains Wi-Fi, Scan, Survey, and Speed Test screens, and adds a dynamic AP Health tab with no hardcoded AP limit, roaming alerts, sticky-client heuristics, ping-pong detection, continuous foreground monitoring and HTTPS-based VoIP readiness estimates.

## Important limitations
- **Stage A only**: floor-plan heatmaps and PDF reports are planned for Stage B, not included in this package.
- Monitoring runs while the app remains in the foreground. No background foreground-service implementation yet.
- AP scan values can be stale due to Android scan throttling. Sticky-client alerts are provisional.
- VoIP readiness is an estimate from HTTPS probes, not actual Teams/RTP measurements.
- Gateway TCP port failures do not establish gateway packet loss.
- Data is held in memory; use Survey CSV export before closing.
- The Gradle wrapper JAR and launcher scripts are not bundled. Copy from your working project.
- **Not compiled or device-tested in this environment.** Keep your working v0.5 backup.
