# Bluetooth

The mesh can run over **real Bluetooth Classic (RFCOMM)** on Linux. Code: `backend/src/main/java/storage/bluetooth/`.

| Part | How |
|---|---|
| Data link | `AF_BLUETOOTH` RFCOMM **kernel sockets** called through the JDK's FFM API (`NativeRfcomm`): listen, accept, connect, read, write. No native library to install, **no root** |
| Adapter, discovery, signal | BlueZ on the system D-Bus via `bluez-dbus` (`BlueZAdapter`): adapter address, device scan with names |
| Finding other Relay devices | While the device is **visible**, the adapter is named **`<display name> [relay:<deviceId>]`** (or just `relay:<deviceId>` without a display name); scans keep only devices with such a name (headphones and phones are ignored) |
| Connecting | Every Relay device listens on RFCOMM channel 22, so no service lookup is needed; the topology controller decides whom to connect to |
| Above the radio | Identical to TCP: `Transport`/`Link`, HELLO, AODV routing, chat, file transfer |

## Using it
Bluetooth is **on by default** (`mesh.transport=both`: TCP plus Bluetooth). It falls back to TCP only, with the reason shown in
the UI, if Bluetooth is unavailable: no adapter, BlueZ not running, or RFCOMM channel 22 already held by another Relay on the
same computer. Your device is **hidden until you make it visible**.

* **Settings → Your device name**: set the name other people see (up to 32 characters, any language). The device id (for example `C`) stays the
  stable internal address and is changed in Settings. Nothing about visibility is on this page.
* **Network map** tab: select **your own device** on the map to get the **Visible to nearby devices** switch. Switching it on
  advertises `<name> [relay:<id>]`; off puts the adapter back. The choice is remembered (`backend/config/device-profile.json`).
* **Network map, nothing selected**: the **Nearby devices** section. *Scan now* starts a Bluetooth scan (about 8 s; scans also run
  every 30 s). Relay devices and Relay peers on the LAN are listed first with signal, state (Connected / Connect / Retry with the
  reason it failed). Other Bluetooth devices are collapsed under one line. Discovered Relay devices also appear on the map as
  dashed nodes; select one for a Connect button. *Connect by address* is for a device on your network (host:port).
* Console (CLI node): `name <text>`, `visible on|off`, `bluetooth`. Options: `-Dmesh.transport=tcp|bluetooth|both`,
  `-Dmesh.bluetooth.channel` (22), `-Dmesh.bluetooth.scanSeconds` (8), `-Dmesh.bluetooth.intervalSeconds` (30).
* API: `GET/POST /api/device`, `GET /api/nearby`, `POST /api/bluetooth/scan`, `POST /api/mesh/connect {deviceId}`; the ones that
  change this device are accepted only from this laptop (the setup agent enforces it, because it is reachable from the LAN).

Requirements: Linux, BlueZ running (`systemctl is-active bluetooth`), Bluetooth switched on, the `rfcomm` kernel module
(`lsmod | grep rfcomm`; it loads on demand), a normal desktop session (D-Bus access to BlueZ).

## What the app changes on your adapter (and restores)
Only while the device is **visible**: the adapter name becomes `<name> [relay:<id>]`, it becomes **discoverable**, and the 180 s
discoverable timeout is removed. The original values are saved to `backend/config/bluetooth-restore.properties` **before**
changing anything and put back when you switch visibility off, or on quit, Ctrl+C, SIGTERM or terminal close. If the process is
killed with `kill -9`, the next start restores them first. Check with `bluetoothctl show | grep -E "Alias|Discoverable"`.
BlueZ applies these changes a moment after they are requested (a read right after a change can still show the old value, and a
very fast second change replaces the first), so the originals are captured once, kept in memory and in the restore file, and never
re-read from an adapter that may be mid-change; an empty alias means "reset to the system name".

## Testing with two real devices
1. Two Linux laptops with Bluetooth, each running the app with `-Dmesh.transport=bluetooth` and **different device ids**
   (CLI: two different config files; or two setup-mode nodes).
2. On each: select your device on the **Network map** and switch **Visible to nearby devices** on; then press **Scan now**. The other should appear under Nearby devices with a Connect button (CLI: `visible on`, then `bluetooth` lists `RELAY device <id>`).
3. Press Connect (devices also connect by themselves once visible); confirm with `neighbours` and `routes`, then `chat <id> hi` and `send-file <id> <path>`.
4. Optional third laptop out of range of the first, in range of the second, to see a relayed message.
Hardware tests in the repo: `NativeRfcommHardwareTest` (real kernel sockets; runs when RFCOMM is supported) and, opt-in,
`./mvnw test -Drelay.hardwareTests=true -Dtest=BlueZAdapterHardwareTest,NativeRfcommHardwareTest` (renames the adapter
for a moment, scans, tries a real connection to a device that is not there).

## What has and has not been verified
Earlier, pre-v2 checks recorded **on real hardware (one laptop, controller `hci0`)**: opening, binding, listening on and closing real RFCOMM sockets
(including waking a thread blocked in accept); a real BlueZ scan listing real nearby devices; advertising as `relay:<id>`
and restoring the original name/discoverable settings on quit, Ctrl+C and a simulated crash; a real connection attempt to a
device that is not there failing with a readable error; the full app running with `-Dmesh.transport=both` and exposing
the adapter and nearby devices through `/api/mesh`.
**Not verified:** a Bluetooth link between **two** devices, because only one adapter was available. The mesh stack
(routing, chat, file transfer, healing) is tested on `BluetoothTransport` using an in-memory fake radio, which proves the
adapter and the layers above it but is **not** Bluetooth.

## Limitations
* Bluetooth Classic only (no BLE); Linux/BlueZ only; one RFCOMM channel (22).
* Scanning is a duty cycle (8 s every 30 s) because inquiry slows connections; discovery is therefore not instant.
* Application entry points now wrap RFCOMM in J-PAKE password authentication and TLS authenticated encryption. Honor system pairing prompts when the stack requires them. The secured two-device Bluetooth path has not been validated on real hardware.
* Signal strength (RSSI) is shown only when BlueZ reports it; link cost for routing is still hop count.
* Visible members advertise public `[relay:<id>] [mesh:<meshId>]` tags. Anyone can spoof these names; the secure carrier must authenticate before HELLO or application traffic.
* Setup mode can scan Bluetooth meshes before membership and authenticate a selected RFCOMM endpoint. See [current implementation and hardware limits](offline-mesh-status.md).
