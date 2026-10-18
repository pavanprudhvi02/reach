#!/usr/bin/env bash
# Walks through routing, failover and streaming against `docker compose up`.
set -euo pipefail
API=${API:-http://localhost:8080}
command -v jq > /dev/null || { echo "demo.sh needs jq"; exit 1; }

pool() {
  curl -s "$API/v1/workers" | jq -r '
    ["WORKER", "HEALTHY", "CIRCUIT", "SERVED", "FAILED", "LATENCY_MS"],
    (.[] | [.id, .healthy, .circuit, .served, .failed, (.latencyMs // "-")])
    | @tsv' | column -t
}

ask() {
  curl -s -D /tmp/reach-headers -o /tmp/reach-body "$API/v1/chat/completions" \
    -H 'content-type: application/json' \
    -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$1 Answer in a few words.\"}],\"max_tokens\":16}"
  worker=$(grep -i '^x-reach-worker' /tmp/reach-headers | tr -d '\r' | cut -d' ' -f2)
  attempts=$(grep -i '^x-reach-attempts' /tmp/reach-headers | tr -d '\r' | cut -d' ' -f2)
  answer=$(jq -r '.choices[0].message.content // .error.message' /tmp/reach-body | tr '\n' ' ' | cut -c1-48)
  printf '  %-16s -> %-9s attempts=%s  %s\n' "$1" "$worker" "$attempts" "$answer"
}

echo "== Worker pool"
pool

echo
echo "== Requests spread across the pool"
for q in "Name a planet." "Name a colour." "Name a fruit."; do ask "$q"; done

echo
echo "== Killing worker-1 (docker compose kill worker-1)"
docker compose kill worker-1 > /dev/null 2>&1
for q in "Name an animal." "Name a city." "Name a metal." "Name a river."; do ask "$q"; done

echo
echo "== Pool after the crash"
sleep 3
pool

echo
echo "== Streaming, token by token"
printf '  '
curl -sN "$API/v1/chat/completions" -H 'content-type: application/json' \
  -d '{"stream":true,"max_tokens":40,"messages":[{"role":"user","content":"Count from one to ten in words."}]}' |
  while IFS= read -r line; do
    case "$line" in
      "data: [DONE]"*) break ;;
      data:*) printf '%s' "${line#data: }" | jq -j '.choices[0].delta.content // empty' | tr '\n' ' ' ;;
    esac
  done
echo

echo
echo "== Restarting worker-1"
docker compose start worker-1 > /dev/null 2>&1
for _ in $(seq 1 60); do
  curl -s "$API/v1/workers" | jq -e '.[] | select(.id == "worker-1") | .healthy' > /dev/null && break
  sleep 2
done
pool
echo
echo "worker-1 is back in the pool."
