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

# send N "question" [max_tokens]: starts a request in the background.
send() {
  local body
  body=$(jq -nc --arg q "$2" --argjson n "${3:-16}" \
    '{max_tokens: $n, ignore_eos: ($n > 16), messages: [{role: "user", content: $q}]}')
  curl -s -D "/tmp/reach-h.$1" -o "/tmp/reach-b.$1" "$API/v1/chat/completions" \
    -H 'content-type: application/json' -d "$body" &
}

# show N "question": prints which worker answered request N and how many attempts it took.
show() {
  local worker attempts answer
  worker=$(grep -i '^x-reach-worker' "/tmp/reach-h.$1" | tr -d '\r' | cut -d' ' -f2)
  attempts=$(grep -i '^x-reach-attempts' "/tmp/reach-h.$1" | tr -d '\r' | cut -d' ' -f2)
  answer=$(jq -r '.choices[0].message.content // .error.message' "/tmp/reach-b.$1" | tr '\n' ' ' | cut -c1-44)
  printf '  %-22s -> %-9s attempts=%s  %s\n' "$2" "$worker" "$attempts" "$answer"
}

ask() { send 0 "$1"; wait; show 0 "$1"; }

echo "== Worker pool"
pool

echo
echo "== Three requests at once spread across the pool"
qs=("Name a planet." "Name a colour." "Name a fruit.")
for i in 0 1 2; do send "$i" "${qs[$i]}"; done
wait
for i in 0 1 2; do show "$i" "${qs[$i]}"; done

echo
echo "== Three long answers in flight, then worker-1 is killed"
qs=("Tell a story about a fox." "Tell a story about a ship." "Tell a story about a robot.")
for i in 0 1 2; do send "$i" "${qs[$i]}" 400; done
sleep 0.6
docker compose kill worker-1 > /dev/null 2>&1
echo "  docker compose kill worker-1"
wait
for i in 0 1 2; do show "$i" "${qs[$i]}"; done

echo
echo "== New requests keep working"
for q in "Name an animal." "Name a city."; do ask "$q"; done

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
