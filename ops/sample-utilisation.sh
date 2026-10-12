#!/usr/bin/env bash
# Samples resource usage of the compose stack until interrupted (Ctrl-C) and prints CSV to stdout.
# Run from the repository root while the stack is up, for example:
#   ops/sample-utilisation.sh > utilisation.csv &   ...load...   kill %1
# One row per container per sample (one sample about every 2 s: `docker stats --no-stream` itself takes ~2 s).
# cpu_percent is docker's figure: 100 = one full CPU core. App rows also carry the instance's own metrics
# from /actuator/prometheus; jvm_live_threads counts platform threads only (virtual threads are not threads
# in that metric). pg_connections is the number of PostgreSQL sessions on the reservecore database.
set -euo pipefail

url_of() { # works with the bash 3.2 that macOS ships (no associative arrays)
  case "$1" in
    reservecore-app1-1) echo http://localhost:8081 ;;
    reservecore-app2-1) echo http://localhost:8082 ;;
    *) echo "" ;;
  esac
}

metric() { # metric <scrape text> <regex for the metric line>: sums matching samples
  printf '%s\n' "$1" | awk -v re="$2" '$0 ~ re { sum += $NF } END { printf "%.0f", sum }'
}

echo "time,container,cpu_percent,mem_mib,hikari_active,hikari_pending,heap_used_mib,jvm_live_threads,http_requests_total,pg_connections"
while true; do
  now=$(date -u +%H:%M:%S)
  pg=$(docker compose exec -T postgres psql -qtA -U reservecore -d reservecore \
    -c "select count(*) from pg_stat_activity where datname = 'reservecore'" 2>/dev/null || echo "")
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
    reservecore-postgres-1 reservecore-app1-1 reservecore-app2-1 2>/dev/null |
  while read -r name cpu mem _; do
    cpu=${cpu%\%}
    mib=$(printf '%s' "$mem" | awk '/GiB/ { sub("GiB", ""); printf "%.0f", $0 * 1024; next } /MiB/ { sub("MiB", ""); printf "%.0f", $0; next } { print 0 }')
    url=$(url_of "$name")
    if [[ -n "$url" ]]; then
      scrape=$(curl -s -m 2 "$url/actuator/prometheus" || true)
      heap=$(metric "$scrape" '^jvm_memory_used_bytes\\{.*area="heap"')
      echo "$now,$name,$cpu,$mib,$(metric "$scrape" '^hikaricp_connections_active\\{'),$(metric "$scrape" '^hikaricp_connections_pending\\{'),$(( ${heap:-0} / 1048576 )),$(metric "$scrape" '^jvm_threads_live_threads'),$(metric "$scrape" '^http_server_requests_seconds_count\\{'),"
    else
      echo "$now,$name,$cpu,$mib,,,,,,$pg"
    fi
  done
done
