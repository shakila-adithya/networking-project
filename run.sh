#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

for tool in java npm; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing $tool. Install JDK 25 and Node.js 24, then retry." >&2
        exit 1
    fi
done

if [[ ${1:-} != --no-build ]]; then
    npm --prefix frontend ci
    npm --prefix frontend run build
    ./mvnw -B verify
else
    shift
    if [[ ! -f frontend/dist/index.html || ! -d target/classes ]]; then
        echo "Build output is missing. Run ./run.sh first." >&2
        exit 1
    fi
fi

# Additional Maven properties, such as -Dsetup.port=6085, may be passed through.
exec ./mvnw -B exec:java -Dexec.mainClass=storage.PeerNode -Dexec.args=--setup "$@"
