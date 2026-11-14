# Relay frontend

Next.js 16 App Router, React 19, TypeScript, and locally bundled Inter fonts. The supplied Figma ZIP specifications are retained under `docs/design-reference/`; their SVG assets live in `public/icons/`.

From the repository root:

```sh
./scripts/run.sh
```

Open http://localhost:6080. The launcher builds the Next static export into `frontend/dist`, runs Java verification (in `backend/`), and serves the app and API from one origin. No Node server or internet access is needed after building.

For frontend development, keep the Java setup agent running and use:

```sh
cd frontend
npm ci
npm run dev
```

Open http://localhost:3000. The development server forwards `/api` to the Java agent on port 6080.

`npm run build` type-checks, prerenders all routes, and copies the export to `dist`. `npm run typecheck` runs TypeScript independently.

## Routes and data

- `/`: network topology with search, device inspection, and zoom.
- `/messages/`: conversations, compose, and live delivery status polling.
- `/files/`: file picking/drop, recipient selection, sending, activity filters, and local file downloads.
- `/settings/`: existing cluster onboarding, validation, and restart controls.

The default **Design preview** shows the supplied sample names, Bluetooth labels, messages, and activity. Its composer and transfers stay in memory and do not send to real peers. Select **Use live network** in the footer to poll the actual Java API. That mode uses the backend's reported transport, neighbours, inbox, delivery states, and stored files; it does not claim Bluetooth hardware support. The mode preference is saved in browser local storage; credentials are not.

The live map only draws local-to-neighbour links reported by `/api/mesh`; device positions are illustrative. The current backend does not expose full remote topology or multi-hop file route progress. Transfers report success only after API confirmation; interrupted or failed transfers show an error. Messaging uses `/api/mesh/chat` and its status endpoint.

The export contains prose layout specifications and icons, with no rendered reference images or x/y coordinates. Dimensions, colors, typography, content, and icons follow the supplied specifications; responsive layouts adapt them below desktop size.

## Browser verification

Keep the built Java-hosted app running on port 6080, then run:

```sh
npx playwright install chromium
npm run test:e2e
```

Use `RELAY_TEST_URL` for another base URL, or `PLAYWRIGHT_CHROMIUM_EXECUTABLE` for an existing Chrome/Chromium executable. Browser tests cover routes/assets, preview messaging, file selection, mobile overflow, and the live chat API contract with intercepted responses. They do not send messages to real peers.
