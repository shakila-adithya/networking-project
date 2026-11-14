# Self-Healing Storage UI

For the complete UI join workflow, build this frontend and start `PeerNode --setup` from the project root. Open `http://localhost:6080` → Settings → Join existing cluster. See [joining device C](../docs/join-device-C.md) for exact steps, prerequisites and firewall/multicast limitations.

Small React + Vite frontend for the existing Java storage system.

Use Node.js 24. From the project root, `./run.sh` builds both parts and starts the UI/API host at http://localhost:6080.

```powershell
cd frontend
npm ci
npm run dev
```

The UI contains Dashboard, Files, Peers, Replication & Repairs, and Settings. Health, uploads, downloads and inventory use the Java REST API. Settings uses the setup agent for network detection, validation, saving and node restart. Metrics not exposed by the API remain marked unavailable. Vite forwards `/api` to the local setup agent at port 6080; the production UI uses the same origin as its host.
