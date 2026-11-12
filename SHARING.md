# Run this project on another Windows computer

This archive contains the Java backend, React frontend, tests and Maven Wrapper. It does not include uploaded files, another device's configuration/secret, Git history, installed dependencies or generated builds.

## Requirements

- JDK 21 or newer, with `java` available in PowerShell.
- Node.js 24 or newer, with `npm` available in PowerShell.
- Internet access for the first Maven/npm dependency downloads.
- A trusted private LAN shared with the existing cluster devices.

Extract the ZIP first (do not run it from inside the ZIP). Open PowerShell inside the extracted `networking-project` folder containing `pom.xml` and `mvnw.cmd`.

## Build and start

```powershell
java -version
node --version
cd frontend
npm ci
npm run build
cd ..
.\mvnw.cmd compile
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.PeerNode' '-Dexec.args=--setup'
```

Keep the terminal open. Open **http://localhost:6080** on that computer. No separate Vite server is required for this route.

## Join the existing cluster

1. Open Settings → Join existing cluster.
2. Enter a unique peer ID, such as C. Never reuse another active device's ID.
3. Enter the existing cluster ID and shared discovery secret. Obtain these from the cluster operator separately; they are intentionally not included in this ZIP.
4. Select the detected local Wi-Fi/Ethernet network shared with the cluster. Keep the suggested available TCP and REST ports.
5. Match the existing cluster's multicast group and discovery UDP port.
6. Select Validate settings, Apply configuration, then Restart local node with saved configuration.
7. Wait for CONNECTED and confirm the existing devices have ALIVE status with verified heartbeats.

A and B must already be running with authenticated discovery enabled. Java may require permission on the Windows Private network for the selected peer TCP port and discovery UDP port. The application does not change firewall settings. Some guest networks/hotspots block multicast or isolate devices; use a LAN that supports peer communication. See `docs/join-device-C.md` for troubleshooting.

Configuration is saved locally to `config/local-setup.json`; it is created by setup and must not be shared. Files uploaded on this computer are stored under `storage-data/<peer-id>`.

## Start again later

From the extracted project folder, run:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.PeerNode' '-Dexec.args=--setup'
```

Open http://localhost:6080 again. Saved configuration loads automatically. Press Ctrl+C in the Java terminal to stop the application. Rebuild the frontend/backend only after their source code changes.

If 6080 is already occupied, stop the older setup instance or start with `'-Dsetup.port=6085'` added to the command and open http://localhost:6085.
