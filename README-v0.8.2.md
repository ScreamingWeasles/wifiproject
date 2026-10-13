# Screaming Weasels v0.8.2 — Quick IP Scan

LAN Device Scanner now offers **IP Only** alongside Gentle, Normal and Thorough.

Choose IP Only and tap **Quick IP Scan** for a shorter scan of the connected subnet.
This mode probes only TCP 80, 443 and 445 with shorter timeouts and shows just responding IP addresses.
It does not identify every device: devices with closed/filtered ports will not be listed.
Use Normal or Thorough for service discovery. Existing persisted results remain intact across tabs.

Source-only package; compile with Android Studio.
