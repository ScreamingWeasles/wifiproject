# Screaming Weasels v0.8 source preview

Based on v0.7.2, preserves previous tabs and adds LAN device discovery and a limited UDP announcement listener to Scan. Requires authorized network use.

LAN scanner: TCP connect attempts on 13 common ports, with progress, Stop and pacing presets. Shows responsive IPs and observed TCP services; does NOT guarantee full inventory, vendor/MAC discovery, DNS names, or vulnerability assessment. Large networks scan only a 256-host local window. Android network restrictions and VLAN isolation affect results.

UDP: listens for mDNS/SSDP announcements delivered to the phone. It does NOT sniff all UDP flows or identify arbitrary streams across the LAN. A phone-only passive listener may see no traffic even on an active network. No packets are sent to other devices by the UDP listener.

The v0.7.2 Teams UDP echo simulation is unchanged and still requires a consenting echo server. The requested standalone Teams mode is NOT yet implemented in this source package.

Compilation: Android SDK/Gradle are unavailable in this execution environment, so this ZIP has NOT been compiled. Open with Android Studio and build there. Gradle wrapper launcher files may need to be copied from your working project.
