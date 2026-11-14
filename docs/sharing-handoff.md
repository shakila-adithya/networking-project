# Run this project on another Windows computer

This archive contains the Java backend, React frontend, tests and Maven Wrapper. It does not include uploaded files, another device's configuration/secret, Git history, installed dependencies or generated builds.

## Requirements

- JDK 21 or newer, with `java` available in PowerShell.
- Node.js 24 or newer, with `npm` available in PowerShell.
- Internet access for the first Maven/npm dependency downloads.
- A trusted private LAN shared with the existing cluster devices.

Extract the ZIP first (do not run it from inside the ZIP). Open PowerShell inside the extracted project, then `cd backend` (it contains `pom.xml` and `mvnw.cmd`); run the Maven commands below from there.

## Build and start

```powershell
java -version
node --version
cd frontend
npm ci
npm run build
cd ..
.\mvnw.cmd compile
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=--setup'
```

Keep the terminal open. Open **http://localhost:6080** on that computer. No separate Vite server is required for this route.

## Join the existing cluster

1. Open the Network map on this device and switch to the live network.
2. Select Scan meshes, choose the mesh and enter its password. Names are unverified until authentication succeeds.
3. Accept any operating-system Bluetooth pairing prompts. Watch the map for authenticated active links.
4. To enter known settings manually, use Settings → Advanced settings → Set up a mesh manually. Choose a unique Device ID and use the same mesh name and password as the other members. Select Save manual settings and reconnect.

All devices must run the updated authenticated protocol. Allow Relay or Java through the firewall on your trusted local network when prompted. Some guest Wi-Fi networks isolate devices. See `docs/join-device-C.md` for troubleshooting.

Configuration is saved locally to `backend/config/local-setup.json`; it is created by setup and must not be shared. Files uploaded on this computer are stored under `backend/storage-data/<peer-id>`.

## Start again later

From the extracted project folder, run:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=--setup'
```

Open http://localhost:6080 again. Saved configuration loads automatically. Press Ctrl+C in the Java terminal to stop the application. Rebuild the frontend/backend only after their source code changes.

If 6080 is already occupied, stop the older setup instance or start with `'-Dsetup.port=6085'` added to the command and open http://localhost:6085.
