#!/usr/bin/env bash
# Gracefully stops any node/agent of this project that is still running (for example after a crashed
# terminal). Normal use needs no script: Ctrl+C, closing the terminal, or typing "quit" in a CLI node
# already ends the process. Sends SIGTERM (the app's shutdown hook closes sockets and threads) and
# waits; it only force-kills with --force.
set -uo pipefail

force=0
[[ ${1:-} == --force ]] && force=1

# Only real java processes running this project's main class (not shells or wrappers that merely
# mention it on their command line).
list() {
    ps -eo pid=,comm=,args= | awk -v self="$$" '$2 == "java" && $1 != self && /storage\.app\.PeerNode/ { print $1 }'
}

pids=$(list)
if [[ -z $pids ]]; then
    echo "No node or setup agent of this project is running."
    exit 0
fi

echo "Stopping:"
ps -o pid=,etime=,args= -p $(echo $pids | tr ' ' ',') | cut -c1-110
kill -TERM $pids 2>/dev/null

for _ in $(seq 1 30); do
    [[ -z $(list) ]] && { echo "All stopped."; exit 0; }
    sleep 0.5
done

if (( force )); then
    echo "Still running after 15 s; forcing."
    kill -KILL $(list) 2>/dev/null
    sleep 1
    [[ -z $(list) ]] && { echo "All stopped."; exit 0; }
fi
echo "Some processes did not stop within 15 s. Re-run with --force to kill them:" >&2
ps -o pid=,args= -p $(list | tr '\n' ',' | sed 's/,$//') | cut -c1-110 >&2
exit 1
