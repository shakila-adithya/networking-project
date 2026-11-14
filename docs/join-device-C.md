# Join device C through the UI

## Prerequisites

- Devices A and B are already running with discovery enabled. They must share the exact cluster ID, discovery secret, multicast group and UDP port. Static-only peers cannot discover C; keep static startup supported, but enable discovery on A and B before using this join flow.
- Device C has the updated source, JDK 21+, and Node/npm for the one-time UI build. Run the Maven commands from the `backend` folder (`cd backend`) and the npm commands from the project root. Do not copy another laptop's `backend/config/local-setup.json` or storage directory.
- All devices are on a trusted private Wi-Fi/Ethernet LAN, can communicate directly, and have synchronized clocks (discovery accepts approximately 30 seconds of clock skew).
- C needs a unique peer ID. Use `C` only if no existing device already uses it. Choose an ID not present in A/B's `peers` output.
- Obtain the existing cluster ID and shared secret from the operator of A/B. Secrets are not retrievable through the setup API. Do not paste secrets into commands, screenshots or source control.

## Start the setup host on C

In PowerShell at the project root:

```powershell
cd frontend
npm ci
npm run build
cd ..
.\mvnw.cmd compile
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=--setup'
```

Open **http://localhost:6080** on C. Keep this terminal running. No existing peer configuration or `ipconfig` output is needed. Stop an older Java peer on C first; A and B can continue running normally. The setup agent remains running even before C has joined or if C's node fails to start.

If 6080 is occupied, use a different setup port:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=--setup' '-Dsetup.port=6085'
```

Then open **http://localhost:6085**. A setup-agent bind failure is a terminal startup error; choose another setup port and start again.

## Join through Settings

1. Open **Settings → Join existing cluster**.
2. Enter **Peer ID C**, the existing **cluster ID**, and **shared discovery secret**. The secret field is masked and never prefilled from disk.
3. Choose the detected network connected to A and B. Private LAN interfaces are listed first. If several interfaces are available (Ethernet, Wi-Fi, VPN), explicitly choose the right network. A single suitable interface can be preselected. Loopback, link-local, down, virtual, IPv6-only and non-multicast interfaces are excluded. If none appear, connect Wi-Fi/Ethernet and select **Refresh networks**.
4. Keep the suggested free **peer TCP port** and **internal REST port**, or enter alternatives. Ports are revalidated on Validate and Apply. They must differ and cannot equal the setup port. A port can be taken by another process between validation and restart; restart errors remain visible in Settings.
5. Match A/B's **discovery multicast group** and **UDP port** (defaults `239.255.42.99` and `45555`).
6. Select **Validate settings**. This checks local settings and port availability; it does not authenticate against A/B or prove multicast connectivity. It saves nothing.
7. Select **Apply configuration**. This explicitly writes C's settings to `backend/config/local-setup.json`. Existing `discovery-lan-*.json` and static configuration files are untouched. If validation or persistence fails, the previous managed configuration remains intact.
8. Finish any transfers on C, then select **Restart local node with saved configuration**. This restarts C's TCP server, internal REST server, discovery, heartbeat, repair and reconciliation services. The UI host remains available. It is a node restart, not an in-place change to an active peer identity; active transfers may be interrupted.
9. Watch **STARTING → DISCOVERING → CONNECTED**. Allow at least 15 seconds. **CONNECTED** requires at least one remote peer with a successful heartbeat. Confirm both A and B appear with `ALIVE` and verified heartbeats. A peer ID collision is shown as **PEER ID CONFLICT**; select a different ID and apply/restart.
10. On A/B, run the existing CLI `peers` and `status` commands to confirm C's identity and TCP endpoint. Upload a small file and verify a second copy and matching hash before trying large files.

After restarting the whole application, launch the same `--setup` command again. The managed configuration is loaded and the node starts automatically. If C has moved to a different network/address, the UI stays reachable; refresh networks and reapply the correct selection.

## Firewall and multicast

No firewall or operating-system settings are changed automatically. On a trusted network, set the Windows network profile to **Private**, allow Java through Windows Firewall on that private network, and have the operator allow inbound **the selected peer TCP port** and **discovery UDP port** from the trusted subnet on each applicable device. Do not disable the firewall or open ports to the public Internet. The internal REST port in setup mode binds to loopback and does not need a LAN firewall rule.

The UI is available on C at `http://<C-address>:6080` for viewing from another trusted device if the setup/UI port is permitted by the OS. Its API uses the same host automatically. Applying credentials or restarting the node is restricted to the local laptop: open `http://localhost:6080` on C for these actions. No shared secret is sent through the LAN UI. The existing data API remains intended for trusted LAN use and has no user authentication or TLS layer.

If no peers appear, check matching cluster ID/secret/group/UDP port, device clocks and chosen network. If discovery sees peers but heartbeats fail, check inbound peer TCP permissions and reachability. Wrong secrets are reported generically as rejected joins; the API never echoes them. Wi-Fi client isolation, guest networks, hotspots, VLANs, routers or VPNs may block multicast or device-to-device traffic. In that case, use a network that supports multicast or the existing static JSON configuration workflow; no unicast seed fallback is implemented.

## Persistence and compatibility

- The managed configuration is stored separately, replaced atomically, and restricted to the current OS user using file permissions/ACLs. Saving fails if the required write or permission changes cannot be made. It is a local plaintext credential, not encrypted storage; keep it private and do not copy it into Git. `.gitignore` excludes it and setup temporary files.
- The browser keeps entered credentials only in component memory, clears them after Apply, and never writes them to browser storage. Setup responses/status omit the saved secret; malformed JSON errors are sanitized.
- Replication factor remains 2. Joining does not automatically rebalance already healthy files onto C. Registry membership remains dynamic and failed peers are not automatically removed.
- Existing file transfers, repair, reconciliation and the original JSON/legacy CLI startup modes remain supported. A/B need not use setup mode. Setup mode is a long-running agent; Ctrl+C stops it. Original CLI mode retains `quit` and all existing commands.

## Frontend development

The built UI at port 6080 is the recommended setup route. Open Settings from the Relay footer. For Next.js development, run `npm run dev` in `frontend` while the setup agent runs. The development `/api` proxy targets port 6080; change its destination in `frontend/next.config.mjs` if using another agent port. Production assets are a static export served by Java. `NEXT_PUBLIC_API_BASE_URL` can override the legacy setup client API origin at build time; the Relay live screens use the same-origin `/api` endpoints. Remove stale Vite environment overrides.

## Verification

Automated: network eligibility filtering; configuration validation; port conflicts; configuration round-trip, replacement and preservation; setup before joining; validate versus apply; secret redaction; cross-origin mutation rejection; restart failure recovery; authenticated identity collisions. A separate integration test starts three isolated agents on one host using temporary storage, confirms authenticated multicast membership and heartbeats, uploads with replication, downloads matching bytes through the stable agent URL, and restarts C with persisted inventory. This test is skipped only when loopback multicast is unsupported. The full Maven suite also exercises existing transfer, replication, discovery, failover and recovery behavior. The React production build is checked with `npm run build`.

Physical three-device testing is still required: verify C discovers both real laptops over the target LAN, A/B discover C, OS firewall rules permit the selected ports, uploaded content has two verified copies, and an application restart reconnects using saved configuration. Automated local tests do not prove the physical network supports multicast or sustained large transfers.
