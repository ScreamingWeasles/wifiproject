# Screaming Weasels Wi-Fi Teaser v0.7.2

Based on v0.7.1 source, preserving all existing tabs and features.

## Speed tab
- Default destination: 8.8.8.8 (Google DNS, **not** a UDP echo endpoint).
- Teams-style video simulation preset: 60 seconds, approximately 640 kbps UDP payload paced as 1,000-byte datagrams.
- Explicit consent checkbox and configurable UDP port (default 50000).
- Simulation reports sent/returned packets, missing echo percentage, mean round-trip time, variation between successive RTTs (not one-way RTP jitter), BSSID changes, elapsed progress, and stop control.
- Uses the selected Android network for the UDP socket. Existing Cloudflare HTTPS speed tests and Google DNS HTTPS timing are unchanged and independent of the custom UDP target.

## Running your own endpoint on Windows
1. On a PC connected to the test network, install Python 3.
2. In a terminal, run: `python udp_echo_server.py --bind 0.0.0.0 --port 50000`
3. Allow inbound UDP 50000 in Windows Firewall **only for the trusted LAN**; avoid exposing the service to the internet.
4. Find the PC's LAN IPv4 address with `ipconfig`.
5. On the phone's Speed tab, enter that address and port 50000, check authorization, and start the 60-second simulation.
6. Stop the server with Ctrl+C after testing.

**Important:** 8.8.8.8 will not echo this UDP test. A result showing missing echoes against 8.8.8.8 does not prove Wi-Fi packet loss. Only interpret metrics when using an authorized echo endpoint. This is *not* actual Teams media traffic or a certified Teams assessment. Android foreground execution and OS network changes can affect pacing.

## Build notes
This source ZIP includes Gradle wrapper properties but not gradle-wrapper.jar or gradlew scripts. Copy those files from your working Android Studio project if needed. The nativeCanvas import from the v0.7.1 manual fix is included. Not compiled or device-tested in this environment.
