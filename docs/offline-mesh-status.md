# Offline mesh implementation and verification

This is a partial implementation of the requested Linux/Windows product, not a claim of completed hardware interoperability. A Windows Bluetooth Classic adapter (Winsock RFCOMM plus radio inquiry/visibility) is now written but has never run on Windows. Windows Wi-Fi Direct/Mobile Hotspot adapters and pre-connection Wi-Fi Direct mesh advertisements are still missing. Linux native radio operations also require two-device validation.

## Using the implemented controls

Start the setup agent and open `http://localhost:6080`. Switch from Design preview to Live. Network map now offers Scan meshes, Create mesh, Join with a password, Leave mesh, and a Hotspot and Wi-Fi Direct panel. Names from UDP, Bluetooth device names and Wi-Fi Direct discovery are unverified. Successful password authentication is required before a carrier reaches mesh HELLO, routing, chat or file services.

A device belongs to one configured mesh at a time; leave before switching. A mesh ID uses 1–80 letters/numbers/dots/underscores/hyphens. Mesh passwords contain 16–256 characters. Creating/joining enables Bluetooth visibility; the existing map visibility switch can hide it again. A device without an active LAN address can create/join using Bluetooth with a loopback-only advertised IP setting. This does not turn a TCP socket into a Bluetooth link.

On Linux, NetworkManager D-Bus supplies real adapter capabilities, Wi-Fi Direct peer discovery/group connection, and hotspot activation/deactivation. Wi-Fi Direct is a separate radio operation: establish the P2P group, accept OS pairing prompts where required, then discover/join the Relay mesh over its IP network. The NetworkManager implementation does not advertise Relay mesh metadata in pre-connection Wi-Fi Direct service discovery. No ordinary LAN is labelled Wi-Fi Direct. Native IP interface labels are applied only after NetworkManager reports an activated Relay-owned connection. Generic IP links remain TCP when the underlying Wi-Fi technology cannot be verified.

Starting/stopping a radio connection requires acknowledging the current-Wi-Fi disconnection warning in the UI and `confirmed: true` in the API. Hotspot credentials are separate from the mesh password. NetworkManager profiles are created with `AddConnectionUnsaved`, with autoconnect disabled. Credentials stay out of command arguments and disk profiles. Shutdown removes only Relay-owned profiles and attempts to restore the previous connection. NetworkManager/polkit permissions, DHCP support and compatible drivers are required; Relay does not elevate privileges or change firewall policy.

## Windows Bluetooth adapter (unverified)

`BluetoothPlatform` picks the implementation by OS. `WindowsRfcomm` implements `RfcommApi` with Winsock `AF_BTH` RFCOMM sockets through FFM (ws2_32.dll, no extra library), with the same close/shutdown safety as the Linux version and a thread-enforced connect timeout. `WindowsBluetoothAdapter` implements `LocalBluetooth`: radio address via `bthprops.cpl`, nearby-device inquiry via Winsock `NS_BTH` lookup, and discoverable/connectable switching restored on close. Everything above the seam (mesh, security, routing, UI) is unchanged.

Known gaps: Windows has no public API to rename the local radio, so a Windows device cannot publish the `[relay:id] [mesh:id]` name tags that Linux peers use to recognise Relay devices. Windows can find Linux Relay devices, but Linux will not list a Windows device by Bluetooth scan; closing this needs an SDP service record or a different discovery path. Inquiry reports no RSSI or pairing state. The code compiled and its address/struct packing is unit-tested on Linux; no socket call has run on Windows.

## Security and protocol migration

`MeshSecurity` uses Bouncy Castle 1.86 J-PAKE with its default NIST 3072-bit group and explicit third-round key confirmation. HKDF-SHA-256 derives a per-connection, purpose-separated PSK. Bouncy Castle TLS then negotiates TLS 1.2 DHE-PSK with AES-128-GCM-SHA-256. TLS implements the encrypted record protocol, key expansion, nonces and authentication tags. Application code does not implement an encryption algorithm or password exchange primitive. Authentication has a 15-second total deadline and bounded concurrent handshakes.

Both mesh carriers (TCP and Bluetooth RFCOMM) and the separate direct TCP file service use this boundary in `ManagedPeer` and the console node. File-service and mesh sessions cannot substitute for one another. Mesh encryption is hop by hop: authenticated mesh members/relays are trusted with forwarded plaintext; this is not end-to-end encryption against mesh members. Knowing a mesh password grants membership, rather than proving an individual person's identity.

Relay v2 public IP advertisements use UDP multicast `239.255.42.100:45556`, TTL 1, across active multicast-capable IPv4 interfaces, including interfaces created after startup. Advertisements contain public mesh/device IDs and the peer port; they contain neither the password nor a password-derived MAC/verifier. Bluetooth aliases contain public `[relay:deviceId] [mesh:meshId]` tags while visible. Discovered addresses remain hints and must pass PAKE before carrying application traffic. Topology dials additionally require the encrypted HELLO device ID to match the discovered ID; spoofed ID/address associations are rejected. HELLO has a separate 10-second deadline, and authenticated links/file clients are bounded. Received datagrams use packet offsets/lengths, size limits, bounded directory capacity and expiry.

The older HMAC `DiscoveryManager` remains as a legacy protocol implementation/test fixture, but application startup no longer launches it with the mesh password. Leaving old peers broadcasting password-derived HMACs would undermine resistance to offline password guesses: upgrade every device together. Saved configuration still uses the `clusterId` and `discoverySecret` keys so existing protected setup files can migrate. Old multicast settings remain parseable but are superseded by the public v2 directory. Settings never returns saved passwords. Existing setup without a password must be updated before a node can start; no insecure fallback is enabled.

For the positional console shortcut, supply the same `RELAY_MESH_PASSWORD` environment variable on each device. JSON examples contain example-only credentials which must be replaced for actual use. The low-level plaintext file-protocol fixtures bind only loopback; unauthenticated `PeerServer` listeners cannot bind a LAN address. Application entry points always enable security.

Browser device, membership, radio, mesh and file APIs require loopback callers and a local Host, reject foreign Origin and cross-site fetch headers, and do not enable wildcard CORS. The setup proxy checks the real caller before forwarding, because the internal REST service otherwise sees only loopback. Passwords remain in component state only while entering a form, never localStorage/sessionStorage. Saved setup uses the existing owner-only atomic file writer. `PeerConfiguration.toString()` redacts credentials.

Leaving writes a separate `.left` marker so restarting the agent cannot silently rejoin. Configuration, peer ID, storage directory, existing files and old metadata remain intact. No backup replication, relay-file retention or redistribution was reintroduced. Mesh transfers keep temporary receiver staging for explicit resume; relays do not write forwarded user files.

## Recovery and status

Topology retains separate addresses for each peer. Only the exact active address is marked connected; the `/api/mesh` response also reports actual link addresses and carrier kinds. Incoming TCP source ports can differ from advertised listening ports, so an advertised candidate is not automatically marked active merely because the peer has some connection.

AODV, bounded chat retries, message IDs, duplicate suppression, TTL, chunk acknowledgements and file SHA-256 checks remain. Authenticated neighbour keepalives run every two seconds. Twelve seconds without any valid received traffic closes the carrier and invalidates routes, even if a radio disappearance has not produced TCP EOF. Health deadlines run independently of blocked health writes. Alternative routes are possible only where a physical path exists. Delivery is not promised across a partition.

## Verification recorded on 2026-11-14

Automated tests exercise PAKE success/wrong passwords, purpose separation, TLS ciphertext tampering, full-size frames, authenticated TCP file upload/download, spoofed announcements, bounded public discovery, local Host/Origin guards, membership persistence and file preservation, exact candidate status, encrypted mixed-carrier forwarding, mid-transfer link loss and alternate routing, relay non-retention, silent links, incomplete-authentication shutdown, and existing transfer integrity/resume/EOF coverage.

The mixed-carrier recovery test uses real loopback TCP plus an explicitly simulated in-memory carrier. The Bluetooth mesh tests use fake RFCOMM endpoints, now wrapped in the same security boundary. Neither establishes real radio interoperability. Browser tests mock membership/radio APIs to verify passwords, preview separation and disconnection acknowledgement without changing the host's connection.

Recorded checks: `mvn -q verify -Drelay.test.nativeRead=true` succeeded; the backend reports 172 passed and 8 skipped (opt-in radio tests and unavailable loopback multicast). Focused directory/membership tests also passed after the final DatagramSocket adjustment. `npm run typecheck`, `npm run build`, and all 20 Playwright tests passed using local Chrome.

The real Linux NetworkManager read-only capability query succeeded and reported `wlo1` (AP capable) and `p2p-dev-wlo1`. The current Wi-Fi was not disconnected to activate a hotspot or P2P group. Existing kernel RFCOMM checks exercise the local host where supported; they are not two-device transfer tests.

| Hardware combination | What was tested |
|---|---|
| Linux to Linux, separate devices | Not tested: no second controlled Relay test device was available |
| Windows to Windows | Not tested: no Windows runtime/devices; Winsock Bluetooth adapter written, never executed; Windows Wi-Fi Direct/hotspot unimplemented |
| Linux to Windows | Not tested; Linux cannot discover a Windows device by name tag (see above) |
| One Linux host, TCP loopback | Authenticated membership, chat/routing/file-service tests |
| One Linux host, NetworkManager | Capability reads only; no hotspot/P2P activation or peer negotiation |
| In-memory and fake RFCOMM | Authentication/forwarding/recovery/transfer algorithms only |

The requested complete product remains blocked on Windows execution testing, Windows Wi-Fi Direct/hotspot (WinRT) adapters, Windows device discoverability, Linux two-device pairing/group/hotspot validation, and cross-platform hardware validation. Pre-connection Wi-Fi Direct mesh service advertisements also remain unimplemented. Building and passing in-memory tests cannot close those gaps.

Reference APIs: [Bouncy Castle J-PAKE](https://downloads.bouncycastle.org/lts-java/docs/bccore-lts8on-2.73.11-javadoc/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.html), [NetworkManager unsaved profiles](https://networkmanager.dev/docs/api/latest/gdbus-org.freedesktop.NetworkManager.Settings.html), [NetworkManager Wi-Fi P2P](https://networkmanager.dev/docs/api/latest/gdbus-org.freedesktop.NetworkManager.Device.WifiP2P.html), [Windows Wi-Fi Direct publisher](https://learn.microsoft.com/en-us/uwp/api/windows.devices.wifidirect.wifidirectadvertisementpublisher).
