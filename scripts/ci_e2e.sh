#!/usr/bin/env bash
# Assertions against the docker compose stack with real llama.cpp workers.
set -euo pipefail
API=${API:-http://localhost:8080}

fail() {
  echo "::error title=e2e::$*"
  exit 1
}

chat() {
  curl -s -D headers.txt -o body.json -w '%{http_code}' "$API/v1/chat/completions" \
    -H 'content-type: application/json' \
    -d "{\"max_tokens\":12,\"messages\":[{\"role\":\"user\",\"content\":\"$1\"}]}"
}

header() { grep -i "^$1:" headers.txt | tr -d '\r' | cut -d' ' -f2; }

echo "== a real model answers through the orchestrator"
code=$(chat "Say hello.")
[ "$code" = "200" ] || fail "expected 200, got $code: $(cat body.json)"
grep -q '"content"' body.json || fail "no content in answer: $(cat body.json)"
echo "served by $(header x-reach-worker): $(cat body.json | head -c 300)"

echo "== requests are spread over the pool"
seen=""
for i in 1 2 3 4 5 6; do
  code=$(chat "Give me a number between 1 and 10.")
  [ "$code" = "200" ] || fail "request $i returned $code"
  seen="$seen $(header x-reach-worker)"
done
distinct=$(echo $seen | tr ' ' '\n' | sort -u | wc -l)
echo "workers used: $seen"
[ "$distinct" -ge 2 ] || fail "expected load spread across workers, got: $seen"

echo "== a worker dies; requests keep succeeding on the others"
docker compose kill worker-1
for i in 1 2 3 4; do
  code=$(chat "Name a colour.")
  [ "$code" = "200" ] || fail "request after kill returned $code: $(cat body.json)"
  w=$(header x-reach-worker)
  [ "$w" != "worker-1" ] || fail "request was answered by the dead worker"
done
sleep 4
curl -s "$API/v1/workers" | tee workers.json
grep -q '"id":"worker-1","url":"[^"]*","healthy":false' workers.json || fail "worker-1 should be marked unhealthy"

echo "== streaming relays server-sent events"
curl -sN "$API/v1/chat/completions" -H 'content-type: application/json' \
  -d '{"stream":true,"max_tokens":8,"messages":[{"role":"user","content":"Count to three."}]}' > stream.txt
grep -q '^data: ' stream.txt || fail "no SSE events: $(head -c 300 stream.txt)"
grep -q '\[DONE\]' stream.txt || fail "stream did not finish"

echo "== the worker comes back and rejoins"
docker compose start worker-1
for i in $(seq 1 60); do
  if curl -s "$API/v1/workers" | grep -q '"id":"worker-1","url":"[^"]*","healthy":true'; then
    echo "worker-1 healthy again"
    exit 0
  fi
  sleep 3
done
fail "worker-1 did not rejoin"
