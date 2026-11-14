#!/usr/bin/env bash
# Builds the frontend and backend, then starts the backend (which also serves the built UI).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

for tool in java npm; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing $tool. Install JDK 25 and Node.js 24, then retry." >&2
        exit 1
    fi
done

if [[ ${1:-} != --no-build ]]; then
    npm --prefix frontend ci
    npm --prefix frontend run build
    (cd backend && ./mvnw -B verify)
else
    shift
    if [[ ! -f frontend/dist/index.html || ! -d backend/target/classes ]]; then
        echo "Build output is missing. Run ./scripts/run.sh first." >&2
        exit 1
    fi
fi

# The backend runs from backend/ (config/, storage-data/) and serves ../frontend/dist.
# Extra Maven properties, such as -Dsetup.port=6085, may be passed through.
cd backend
exec ./mvnw -B exec:java -Dexec.mainClass=storage.app.PeerNode -Dexec.args=--setup "$@"
