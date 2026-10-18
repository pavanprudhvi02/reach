#!/usr/bin/env bash
# Walks through routing and failover against `docker compose up`.
set -euo pipefail
API=${API:-http://localhost:8080}

ask() {
  curl -s -D /tmp/reach-headers -o /tmp/reach-body "$API/v1/chat/completions" \
    -H 'content-type: application/json' \
    -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$1\"}],\"max_tokens\":24}"
  worker=$(grep -i '^x-reach-worker' /tmp/reach-headers | tr -d '\r' | cut -d' ' -f2)
  attempts=$(grep -i '^x-reach-attempts' /tmp/reach-headers | tr -d '\r' | cut -d' ' -f2)
  answer=$(sed -n 's/.*"content":"\([^"]*\)".*/\1/p' /tmp/reach-body | head -1)
  printf '  %-9s attempts=%s  %s\n' "$worker" "$attempts" "$answer"
}

echo "== Worker pool"
curl -s "$API/v1/workers"; echo

echo
echo "== Three requests, spread across workers"
for q in "Name a planet." "Name a colour." "Name a fruit."; do ask "$q"; done

echo
echo "== Stopping worker-1 mid-flight"
docker compose stop worker-1 > /dev/null
for q in "Name an animal." "Name a city." "Name a metal."; do ask "$q"; done

echo
echo "== Pool after the failure"
curl -s "$API/v1/workers"; echo

echo
echo "== Streaming"
curl -sN "$API/v1/chat/completions" -H 'content-type: application/json' \
  -d '{"stream":true,"max_tokens":16,"messages":[{"role":"user","content":"Count to five."}]}' | head -n 8

docker compose start worker-1 > /dev/null
echo
echo "worker-1 restarted; it rejoins once its health check passes."
