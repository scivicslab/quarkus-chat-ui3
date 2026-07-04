#!/usr/bin/env bash
# End-to-end test: launch chatui3, send the weather-forecast prompt, confirm an answer returns.
# Cleans up the server process on exit (no rogue processes left behind).
set -u

# Configure the vLLM endpoint via env: VLLM=http://<host>:<port> ./e2e-weather.sh
JAR=${JAR:-target/quarkus-chat-ui3-1.0.0-SNAPSHOT.jar}
PORT=28123
BASE=http://127.0.0.1:$PORT
VLLM=${VLLM:-http://vllm:8000}
MODEL=${MODEL:-google/gemma-4-26B-A4B-it}
PROMPT='土日の天気予報をwebで調べて教えてください'
WORKDIR=$(mktemp -d)
SSE=$WORKDIR/sse.log
SRV=$WORKDIR/server.log

cleanup() {
  [ -n "${SSE_PID:-}" ] && kill "$SSE_PID" 2>/dev/null
  [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT

echo "== starting chatui3 on :$PORT (vllm=$VLLM model=$MODEL) =="
java -Dquarkus.http.port=$PORT \
     -Dchatui3.vllm-base-url=$VLLM \
     -Dchatui3.model=$MODEL \
     -jar "$JAR" > "$SRV" 2>&1 &
SRV_PID=$!

# Wait for health (max 30s).
for i in $(seq 1 60); do
  if curl -sf -m 2 "$BASE/health" >/dev/null 2>&1; then echo "== server up =="; break; fi
  if ! kill -0 "$SRV_PID" 2>/dev/null; then echo "!! server died at startup"; tail -30 "$SRV"; exit 1; fi
  sleep 0.5
done

# Confirm the model list resolves through the running server (proves vLLM connectivity).
echo "== /api/models =="
curl -s -m 10 "$BASE/api/models"; echo

# Subscribe to the SSE stream before sending the prompt.
curl -sN -m 300 "$BASE/api/chat/stream" > "$SSE" 2>&1 &
SSE_PID=$!
sleep 1

echo "== POST prompt: $PROMPT =="
curl -s -m 10 -X POST "$BASE/api/chat" \
     -H 'Content-Type: application/json' \
     -d "$(printf '{"text": %s, "source": "browser"}' "$(printf '%s' "$PROMPT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")"
echo

# Wait for a result event (max 240s — 26B model + web_search + multi-step loop).
echo "== waiting for result event =="
RESULT=""
for i in $(seq 1 240); do
  if grep -q '"type":"result"' "$SSE" 2>/dev/null; then
    RESULT=$(grep '"type":"result"' "$SSE" | tail -1)
    echo "== RESULT RECEIVED after ~${i}s =="
    break
  fi
  if grep -q '"type":"error"' "$SSE" 2>/dev/null; then
    echo "!! ERROR event:"; grep '"type":"error"' "$SSE" | tail -3
  fi
  sleep 1
done

echo
echo "======== FINAL ANSWER ========"
if [ -n "$RESULT" ]; then
  printf '%s' "$RESULT" | python3 -c 'import json,sys
for line in sys.stdin:
    line=line.strip()
    if line.startswith("data:"): line=line[5:].strip()
    if not line: continue
    try:
        o=json.loads(line); print(o.get("content") or o.get("text") or o)
    except Exception: print(line)'
  echo "======== E2E: PASS ========"
  RC=0
else
  echo "(no result received within timeout)"
  echo "---- server log tail ----"; tail -40 "$SRV"
  echo "---- sse tail ----"; tail -20 "$SSE"
  echo "======== E2E: FAIL ========"
  RC=1
fi

exit $RC
