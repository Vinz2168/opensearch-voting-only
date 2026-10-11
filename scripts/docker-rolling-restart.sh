#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#
# Manual 2 + 1 scenario with Docker Compose: two full cluster-manager nodes (os-full-1, os-full-2)
# and one voting-only tiebreaker (os-tiebreaker).
#
#   A. Rolling restart of the two full nodes, waiting for each one to rejoin.
#   B. "Fast" rolling restart: os-full-2 is stopped while os-full-1 is still starting, so the
#      tiebreaker holds a newer cluster state than the only surviving full node (invariant I5).
#
# Throughout, a monitor polls _cat/cluster_manager (local view) on every reachable node and fails
# the run if the tiebreaker is ever reported as the cluster manager (invariant I1).
#
# Usage: scripts/docker-rolling-restart.sh [--rounds N] [--keep]
#   --keep    leave the cluster running at the end (default: docker compose down -v)
#
# Requires: docker (with compose), curl, JDK 21 for the plugin build (JAVA_HOME).

set -euo pipefail

ROUNDS=2
KEEP=false
while [ $# -gt 0 ]; do
  case "$1" in
    --rounds) ROUNDS="$2"; shift 2 ;;
    --keep) KEEP=true; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOCKER_DIR="$ROOT/docker"
COMPOSE=(docker compose -f "$DOCKER_DIR/docker-compose.yml" -p voting-only-demo)
WORK_DIR="$(mktemp -d)"
VIOLATIONS="$WORK_DIR/violations.log"
OBSERVATIONS="$WORK_DIR/observations.log"
: > "$VIOLATIONS"
: > "$OBSERVATIONS"

port_of() {
  case "$1" in
    os-full-1) echo 9201 ;;
    os-full-2) echo 9202 ;;
    os-tiebreaker) echo 9203 ;;
  esac
}

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }

fail() {
  log "FAILED: $*"
  exit 1
}

# local view of the elected cluster manager on one node (empty if none or unreachable)
cluster_manager_seen_by() {
  curl -fsS --max-time 2 "http://localhost:$(port_of "$1")/_cat/cluster_manager?h=node&local=true" 2>/dev/null | tr -d '[:space:]' || true
}

node_count_seen_by() {
  curl -fsS --max-time 2 "http://localhost:$(port_of "$1")/_cat/nodes?h=name&local=true" 2>/dev/null | grep -c . || true
}

# wait until $1 sees a cluster manager that is one of the full nodes in "$2" and $3 nodes in total
wait_for_cluster() {
  local via="$1" expected_managers="$2" expected_nodes="$3" deadline=$((SECONDS + 180))
  while [ $SECONDS -lt $deadline ]; do
    local cm count
    cm="$(cluster_manager_seen_by "$via")"
    count="$(node_count_seen_by "$via")"
    if [ -n "$cm" ] && [[ " $expected_managers " == *" $cm "* ]] && [ "$count" = "$expected_nodes" ]; then
      log "cluster formed (via $via): cluster manager [$cm], $count nodes"
      return 0
    fi
    sleep 1
  done
  fail "timed out waiting for a cluster manager in [$expected_managers] with $expected_nodes nodes (via $via)"
}

monitor() {
  while true; do
    for node in os-full-1 os-full-2 os-tiebreaker; do
      local cm
      cm="$(cluster_manager_seen_by "$node")"
      if [ -n "$cm" ]; then
        echo "$(date '+%H:%M:%S') $node sees $cm" >> "$OBSERVATIONS"
        if [ "$cm" = "os-tiebreaker" ]; then
          echo "$(date '+%H:%M:%S') $node reports os-tiebreaker as cluster manager" >> "$VIOLATIONS"
        fi
      fi
    done
    sleep 0.5
  done
}

create_index() {
  local via="$1" name="$2"
  curl -fsS --max-time 30 -X PUT "http://localhost:$(port_of "$via")/$name" -H 'Content-Type: application/json' \
    -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}' > /dev/null
  log "created index [$name] via $via"
}

index_exists_on() {
  local via="$1" name="$2"
  curl -fsS --max-time 5 "http://localhost:$(port_of "$via")/_cluster/state/metadata/$name?local=true&filter_path=metadata.indices.$name.state" 2>/dev/null \
    | grep -q '"open"'
}

cleanup() {
  [ -n "${MONITOR_PID:-}" ] && kill "$MONITOR_PID" 2>/dev/null || true
  if [ "$KEEP" = false ]; then
    "${COMPOSE[@]}" down -v > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

log "building the plugin"
(cd "$ROOT" && ./gradlew --quiet bundlePlugin)
cp "$(ls "$ROOT"/build/distributions/opensearch-voting-only-*.zip | head -1)" "$DOCKER_DIR/plugin.zip"

log "starting the 2 + 1 cluster"
"${COMPOSE[@]}" down -v > /dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build
wait_for_cluster os-tiebreaker "os-full-1 os-full-2" 3

curl -fsS "http://localhost:9201/_cat/nodes?v&h=name,node.role,cluster_manager"
curl -fsS "http://localhost:9201/_cat/plugins?v"

monitor &
MONITOR_PID=$!

other_full() { [ "$1" = os-full-1 ] && echo os-full-2 || echo os-full-1; }

log "=== scenario A: rolling restart of the full nodes ($ROUNDS rounds) ==="
for round in $(seq 1 "$ROUNDS"); do
  for node in os-full-1 os-full-2; do
    other="$(other_full "$node")"
    log "[A$round] stopping $node"
    "${COMPOSE[@]}" stop "$node" > /dev/null
    wait_for_cluster os-tiebreaker "$other" 2
    create_index "$other" "a-$round-$node"
    log "[A$round] starting $node"
    "${COMPOSE[@]}" start "$node" > /dev/null
    wait_for_cluster os-tiebreaker "os-full-1 os-full-2" 3
  done
done

log "=== scenario B: fast rolling restart, stale full node (I5) ==="
for round in $(seq 1 "$ROUNDS"); do
  log "[B$round] stopping os-full-1"
  "${COMPOSE[@]}" stop os-full-1 > /dev/null
  wait_for_cluster os-tiebreaker "os-full-2" 2
  create_index os-full-2 "b-$round"
  log "[B$round] starting os-full-1 and immediately stopping os-full-2 (os-full-1 misses index b-$round)"
  "${COMPOSE[@]}" start os-full-1 > /dev/null
  "${COMPOSE[@]}" stop os-full-2 > /dev/null
  wait_for_cluster os-tiebreaker "os-full-1" 2
  index_exists_on os-full-1 "b-$round" || fail "os-full-1 did not recover index b-$round from the tiebreaker"
  log "[B$round] os-full-1 is cluster manager and has index b-$round"
  "${COMPOSE[@]}" start os-full-2 > /dev/null
  wait_for_cluster os-tiebreaker "os-full-1 os-full-2" 3
done

kill "$MONITOR_PID" 2>/dev/null || true
wait "$MONITOR_PID" 2>/dev/null || true
MONITOR_PID=

"${COMPOSE[@]}" logs --no-color > "$WORK_DIR/compose.log" 2>&1 || true
log "container logs saved to $WORK_DIR/compose.log"

curl -fsS "http://localhost:9201/_cat/nodes?v&h=name,node.role,cluster_manager"

PUSH_ELECTIONS="$(grep -c 'only to propagate cluster state' "$WORK_DIR/compose.log" || true)"
OBSERVED="$(wc -l < "$OBSERVATIONS" | tr -d ' ')"
log "monitor samples: $OBSERVED; tiebreaker temporary elections (I5) logged: $PUSH_ELECTIONS"

if [ -s "$VIOLATIONS" ]; then
  cat "$VIOLATIONS"
  fail "the tiebreaker was reported as cluster manager"
fi
log "PASSED: the voting-only tiebreaker never became the cluster manager"
