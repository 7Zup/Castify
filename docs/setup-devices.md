# Device setup (one time)

Android Studio is not needed: the agent builds and installs from the terminal.

## Phone (USB)

1. Settings → About phone → tap **Build number** 7 times.
2. Settings → System → Developer options → enable **USB debugging**.
3. Plug the phone into the Mac, accept **Allow USB debugging** (tick "Always allow").
4. `adb devices` must show the phone as `device` (not `unauthorized`).

## TV (Chromecast with Google TV, over Wi-Fi)

1. Settings → System → About → scroll to **Android TV OS build** → press OK 7 times.
2. Settings → System → Developer options → enable **USB debugging** (it also covers
   network debugging on this device).
3. Settings → Network & Internet → your Wi-Fi → note the **IP address**.
4. On the Mac: `adb connect <tv-ip>:5555`, then accept the prompt on the TV
   ("Always allow from this computer").
5. `adb devices` must show `<tv-ip>:5555  device`.

The TV IP may change after a router restart: re-run `adb connect`. The agent can find
it from the Mac without asking (the TV is named `Chromecast-HD-…`):

```fish
dns-sd -Z _googlecast._tcp local     # Ctrl-C after 3 s, note the Chromecast-HD target host
dns-sd -G v4 <target-host>.local     # prints the IPv4
```

The current IPs of the user's devices are in the gitignored `.kiro/steering/local.md`.

If `adb connect` says `No route to host` / `Connection refused` while the TV answers
ping, port 5555 is closed: **USB debugging** is off in Developer options.

### This TV uses Wireless debugging (not port 5555)

The user's Chromecast exposes **Wireless debugging** (TLS, random port, pairing code):

1. On the TV: Developer options → Wireless debugging → on → **Pair device with pairing
   code**. It shows `IP:port` (pairing port) and a 6-digit code.
2. `adb pair <ip>:<pairing-port> <code>` (once per computer).
3. Connect port (changes when Wireless debugging restarts): find it with
   `dns-sd -B _adb-tls-connect._tcp local` then `dns-sd -L <instance> _adb-tls-connect._tcp local`,
   or read it on the Wireless debugging screen. Then `adb connect <ip>:<connect-port>`.

### Ad-blocking DNS on the TV

The phone uses AdGuard DNS; the TV had no private DNS (ads inside streams played on the TV
only). Set on 11/05/2026 (previous values: `null` / `null`):

```fish
adb -s <tv> shell settings put global private_dns_specifier dns.adguard.com
adb -s <tv> shell settings put global private_dns_mode hostname
# revert:
adb -s <tv> shell settings put global private_dns_mode off
```

Check: `adb -s <tv> shell ping -c 1 doubleclick.net` must resolve to `127.0.0.1`.

macOS: if `adb connect` says `No route to host` while `nc -z <ip> <port>` succeeds, the adb
server lacks Local Network permission: `adb kill-server; adb start-server` from the
terminal, then retry.
