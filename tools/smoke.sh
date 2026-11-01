#!/usr/bin/env bash
# Every case here is offline: the fixtures are the shapes Gatling actually writes (see README for where each shape
# was read from), and the assertions are the numbers a build would gate on.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# The fixture below is a real Gatling 3.8.3 global_stats.json (from a committed report in Bahmni/performance-test,
# js/global_stats.json) with the numbers replaced: the key layout, nesting and the "t ≥ 800 ms <br>" group labels are
# byte-for-byte what Gatling writes.
stats() { # stats <file> <total> <ok> <ko> <mean> <p50> <p75> <p95> <p99> <max>
  mkdir -p "$(dirname "$1")"
  cat >"$1" <<JSON
{
    "name": "All Requests",
    "numberOfRequests": {
        "total": ${2},
        "ok": ${3},
        "ko": ${4}
    },
    "minResponseTime": {
        "total": 27,
        "ok": 27,
        "ko": 221
    },
    "maxResponseTime": {
        "total": ${10},
        "ok": ${10},
        "ko": 273
    },
    "meanResponseTime": {
        "total": ${5},
        "ok": ${5},
        "ko": 247
    },
    "standardDeviation": {
        "total": 146,
        "ok": 146,
        "ko": 26
    },
    "percentiles1": {
        "total": ${6},
        "ok": ${6},
        "ko": 247
    },
    "percentiles2": {
        "total": ${7},
        "ok": ${7},
        "ko": 260
    },
    "percentiles3": {
        "total": ${8},
        "ok": ${8},
        "ko": 270
    },
    "percentiles4": {
        "total": ${9},
        "ok": ${9},
        "ko": 272
    },
    "group1": {
    "name": "t < 800 ms",
    "count": ${3},
    "percentage": 99
},
    "group2": {
    "name": "t ≥ 800 ms <br> t < 1200 ms",
    "count": 0,
    "percentage": 0
},
    "group3": {
    "name": "t ≥ 1200 ms",
    "count": 0,
    "percentage": 0
},
    "group4": {
    "name": "failed",
    "count": ${4},
    "percentage": 1
},
    "meanNumberOfRequestsPerSecond": {
        "total": 19.107142857142858,
        "ok": 19.104910714285715,
        "ko": 0.002232142857142857
    }
}
JSON
}

# --- 1. global_stats.json: the counts, the percentiles, and a page that stands on its own ------------------------
ws="$(mktemp -d)"
stats "$ws/report-a/js/global_stats.json" 1000 990 10 120 50 90 300 600 4000
printf '{"pattern": "**/global_stats.json", "label": "Checkout load"}' >"$ws/input.json"
run "$ws" >"$ws/log.txt" 2>&1 || fail "run failed: $(cat "$ws/log.txt")"
[ "$(status "$ws")" = "success" ] || fail "status: $(cat "$ws/output.json")"
[ "$(output "$ws" simulations)" = "1" ] || fail "simulations: $(cat "$ws/output.json")"
[ "$(output "$ws" requests)" = "1000" ] || fail "requests: $(cat "$ws/output.json")"
[ "$(output "$ws" okRequests)" = "990" ] || fail "ok: $(cat "$ws/output.json")"
[ "$(output "$ws" koRequests)" = "10" ] || fail "ko: $(cat "$ws/output.json")"
[ "$(output "$ws" koPercent)" = "1.0" ] || fail "koPercent: $(cat "$ws/output.json")"
[ "$(output "$ws" meanResponseTimeMs)" = "120" ] || fail "mean: $(cat "$ws/output.json")"
[ "$(output "$ws" p95ResponseTimeMs)" = "300" ] || fail "p95 from percentiles3: $(cat "$ws/output.json")"
[ "$(output "$ws" p99ResponseTimeMs)" = "600" ] || fail "p99 from percentiles4: $(cat "$ws/output.json")"
[ "$(output "$ws" maxResponseTimeMs)" = "4000" ] || fail "max: $(cat "$ws/output.json")"
[ "$(output "$ws" assertionTotal)" = "0" ] || fail "assertions: $(cat "$ws/output.json")"
[ "$(output "$ws" thresholdBreaches)" = "0" ] || fail "no threshold was set: $(cat "$ws/output.json")"
page="$(output "$ws" report.path)/$(output "$ws" report.target)"
[ -f "$page" ] || fail "report target does not exist: $page"
grep -q '<h1>Checkout load</h1>' "$page" || fail "the label is not in the page"
grep -q '<b>1000</b><span>requests</span>' "$page" || fail "requests card"
grep -q '<b>990</b><span>ok</span>' "$page" || fail "ok card"
grep -q '<b>10</b><span>ko</span>' "$page" || fail "ko card"
grep -q '<b>300 ms</b><span>p95</span>' "$page" || fail "p95 card"
grep -q 'All Requests' "$page" || fail "the request name Gatling wrote is missing"
grep -q '<title>Checkout load</title>' "$page" || fail "no title"
# self-contained: no scripts, no remote assets, no fonts
grep -qE '<script|https?://|src=' "$page" && fail "the page must not reference anything external"
grep -q '</body></html>' "$page" || fail "page is not closed"

# --- 2. thresholds at the boundary do not fire (a limit is a maximum, not an exclusive bound) -------------------
printf '{"pattern": "**/global_stats.json", "label": "B", "maxFailureCount": "10", "maxFailurePercent": "1.0",
         "maxMeanResponseTimeMs": "120", "maxPercentile95Ms": "300", "maxPercentile99Ms": "600"}' >"$ws/input.json"
run "$ws" >"$ws/log.txt" 2>&1 || fail "thresholds equal to the actual values must pass: $(cat "$ws/output.json")"
[ "$(output "$ws" thresholdBreaches)" = "0" ] || fail "breaches at the boundary: $(cat "$ws/output.json")"
grep -q 'no threshold breached' "$ws/output.json" || fail "summary: $(cat "$ws/output.json")"
grep -q '<span>thresholds met</span>\|all gatling thresholds met' "$page" || fail "the page must say the gate passed"

# --- 3. every gate that is exceeded is named, and the page is still written --------------------------------------
printf '{"pattern": "**/global_stats.json", "label": "B", "maxFailureCount": "9", "maxFailurePercent": "0.5",
         "maxMeanResponseTimeMs": "119", "maxPercentile95Ms": "299", "maxPercentile99Ms": "599"}' >"$ws/input.json"
run "$ws" >"$ws/log.txt" 2>&1 && fail "a breached threshold must fail the step"
[ "$(status "$ws")" = "failure" ] || fail "status: $(cat "$ws/output.json")"
[ "$(output "$ws" thresholdBreaches)" = "5" ] || fail "breaches: $(cat "$ws/output.json")"
for gate in maxFailureCount maxFailurePercent maxMeanResponseTimeMs maxPercentile95Ms maxPercentile99Ms; do
  grep -q "$gate breached" "$ws/output.json" || fail "$gate must be named: $(cat "$ws/output.json")"
done
grep -q 'maxFailurePercent breached: 1.0% > 0.5%' "$ws/output.json" || fail "the percent breach must show both numbers"
grep -q 'maxPercentile95Ms breached: 300 ms > 299 ms' "$ws/output.json" || fail "the p95 breach must show both numbers"
[ -f "$page" ] || fail "a failed gate must still publish the page that explains it"
[ "$(grep -o 'BREACH' "$page" | wc -l | tr -d ' ')" = "5" ] || fail "the page must mark 5 breaches"
grep -q 'thresholds breached' "$page" || fail "no breach banner in the page"

# --- 4. two simulations, gatling assertions, and the aggregate the gate looks at ---------------------------------
ws2="$(mktemp -d)"
stats "$ws2/a/js/global_stats.json" 1000 990 10 120 50 90 300 600 4000
stats "$ws2/b/js/global_stats.json" 500 480 20 200 70 120 500 900 3000
# assertions.json as Gatling's AssertionsJsonTemplate writes it: result is a json boolean, values are arrays
cat >"$ws2/b/js/assertions.json" <<'JSON'
{
  "simulation": "simulations.Checkout",
  "simulationId": "checkout-20240102030405",
  "start": 1700000000000,
  "description": "load test",
  "scenarios": ["Checkout"],
  "assertions": [
{"path": "Global", "target": "max of response time", "condition": "is less than", "expectedValues": [5000], "result": true, "message": "", "actualValue": [4000]},
{"path": "Global", "target": "mean of response time", "condition": "is less than", "expectedValues": [400], "result": true, "message": "", "actualValue": [147]},
{"path": "Global", "target": "percent of failed requests", "condition": "is less than", "expectedValues": [1], "result": false, "message": "percent of failed requests is 2.00% but should be less than 1%", "actualValue": [2]}
  ]
}
JSON
printf '{"pattern": "**/global_stats.json", "label": "Two sims", "failOnAssertionFailure": "false",
         "markUnstable": "false"}' >"$ws2/input.json"
run "$ws2" >"$ws2/log.txt" 2>&1 || fail "run failed: $(cat "$ws2/log.txt")"
[ "$(output "$ws2" simulations)" = "2" ] || fail "simulations: $(cat "$ws2/output.json")"
[ "$(output "$ws2" requests)" = "1500" ] || fail "requests must be summed: $(cat "$ws2/output.json")"
[ "$(output "$ws2" okRequests)" = "1470" ] || fail "ok: $(cat "$ws2/output.json")"
[ "$(output "$ws2" koRequests)" = "30" ] || fail "ko: $(cat "$ws2/output.json")"
[ "$(output "$ws2" koPercent)" = "2.0" ] || fail "koPercent: $(cat "$ws2/output.json")"
[ "$(output "$ws2" meanResponseTimeMs)" = "146.7" ] || fail "mean must be request-weighted: $(cat "$ws2/output.json")"
[ "$(output "$ws2" p95ResponseTimeMs)" = "500" ] || fail "p95 must be the worst simulation: $(cat "$ws2/output.json")"
[ "$(output "$ws2" p99ResponseTimeMs)" = "900" ] || fail "p99: $(cat "$ws2/output.json")"
[ "$(output "$ws2" assertionTotal)" = "3" ] || fail "assertions: $(cat "$ws2/output.json")"
[ "$(output "$ws2" assertionPassed)" = "2" ] || fail "passed assertions: $(cat "$ws2/output.json")"
[ "$(output "$ws2" assertionFailed)" = "1" ] || fail "failed assertions: $(cat "$ws2/output.json")"
page2="$(output "$ws2" report.path)/$(output "$ws2" report.target)"
grep -q 'percent of failed requests is 2.00% but should be less than 1%' "$page2" || fail "assertion message missing"
grep -q '<td class="fail">FAILED</td>' "$page2" || fail "the failed assertion must be marked"
grep -q '<b>2/3</b><span>assertions</span>' "$page2" || fail "assertion card"
grep -q 'assertion' "$page2" || fail "no assertion table"

# assertions fail the step by default
printf '{"pattern": "**/global_stats.json", "label": "Two sims"}' >"$ws2/input.json"
run "$ws2" >"$ws2/log.txt" 2>&1 && fail "a failed gatling assertion must fail the step by default"
grep -q 'gatling assertion failed: 1 of 3 assertion(s)' "$ws2/output.json" || fail "assertion breach: $(cat "$ws2/output.json")"
[ "$(output "$ws2" thresholdBreaches)" = "1" ] || fail "breaches: $(cat "$ws2/output.json")"

# --- 5. simulation.log, the tab separated records Gatling wrote through 3.8.x ----------------------------------
# Fixture copies the record bytes of a real 3.8.3 log: RUN <class> <id> <start> <description> <version>,
# USER <scenario> START <ts>, REQUEST <group> <name> <start> <end> <status> <message>.
ws3="$(mktemp -d)"; mkdir -p "$ws3/checkoutsimulation-20240102030405"
printf 'RUN\tsimulations.CheckoutSimulation\tcheckout-20240102030405\t1700000000000\tload test\t3.8.3\n' >>"$ws3/checkoutsimulation-20240102030405/simulation.log"
printf 'USER\tCheckout\tSTART\t1700000000100\n' >>"$ws3/checkoutsimulation-20240102030405/simulation.log"
printf 'REQUEST\t\tGET /cart\t1700000000100\t1700000000200\tOK\t \n' >>"$ws3/checkoutsimulation-20240102030405/simulation.log"
printf 'REQUEST\tcart\tGET /cart\t1700000000200\t1700000000400\tOK\t \n' >>"$ws3/checkoutsimulation-20240102030405/simulation.log"
printf 'REQUEST\t\tPOST /checkout\t1700000000400\t1700000000700\tOK\t \n' >>"$ws3/checkoutsimulation-20240102030405/simulation.log"
printf 'REQUEST\t\tPOST /checkout\t1700000000700\t1700000001100\tKO\tstatus.find.is(200), but actually found 500\n' >>"$ws3/checkoutsimulation-20240102030405/simulation.log"
printf '{"pattern": "**/simulation.log", "label": "From the log"}' >"$ws3/input.json"
run "$ws3" >"$ws3/log.txt" 2>&1 || fail "log run failed: $(cat "$ws3/log.txt")"
[ "$(output "$ws3" requests)" = "4" ] || fail "requests from log: $(cat "$ws3/output.json")"
[ "$(output "$ws3" okRequests)" = "3" ] || fail "ok from log: $(cat "$ws3/output.json")"
[ "$(output "$ws3" koRequests)" = "1" ] || fail "ko from log: $(cat "$ws3/output.json")"
[ "$(output "$ws3" koPercent)" = "25.0" ] || fail "koPercent from log: $(cat "$ws3/output.json")"
[ "$(output "$ws3" meanResponseTimeMs)" = "250" ] || fail "mean from log (100+200+300+400)/4: $(cat "$ws3/output.json")"
[ "$(output "$ws3" p95ResponseTimeMs)" = "400" ] || fail "p95 from log: $(cat "$ws3/output.json")"
[ "$(output "$ws3" maxResponseTimeMs)" = "400" ] || fail "max from log: $(cat "$ws3/output.json")"
grep -q '"build.result":{"type":"string","value":"UNSTABLE"}' "$ws3/output.json" \
  || fail "failures without a threshold must ask for UNSTABLE: $(cat "$ws3/output.json")"
page3="$(output "$ws3" report.path)/$(output "$ws3" report.target)"
grep -q 'POST /checkout' "$page3" || fail "per request row missing from the log report"
grep -q 'GET /cart' "$page3" || fail "per request row missing from the log report"
grep -q 'simulation.log (text)' "$page3" || fail "the page must say where the numbers came from"

# --- 5b. the percentile is a percentile, not the max: 20 responses, p95 = 190 ms, max = 200 ms -------------------
ws3b="$(mktemp -d)"; mkdir -p "$ws3b/run"
printf 'RUN\tsimulations.PercentileCheck\tpercentile-check\t1700000000000\tp95 check\t3.8.3\n' >"$ws3b/run/simulation.log"
for i in $(seq 1 20); do
  start=$((1700000000000 + i * 1000))
  printf 'REQUEST\t\tGET /item\t%s\t%s\tOK\t \n' "$start" "$((start + i * 10))" >>"$ws3b/run/simulation.log"
done
printf '{"pattern": "**/simulation.log", "label": "Percentiles"}' >"$ws3b/input.json"
run "$ws3b" >"$ws3b/log.txt" 2>&1 || fail "percentile run failed: $(cat "$ws3b/log.txt")"
[ "$(output "$ws3b" requests)" = "20" ] || fail "requests: $(cat "$ws3b/output.json")"
[ "$(output "$ws3b" meanResponseTimeMs)" = "105" ] || fail "mean of 10..200: $(cat "$ws3b/output.json")"
[ "$(output "$ws3b" p95ResponseTimeMs)" = "190" ] || fail "p95 nearest-rank over 20 samples must be 190: $(cat "$ws3b/output.json")"
[ "$(output "$ws3b" p99ResponseTimeMs)" = "200" ] || fail "p99: $(cat "$ws3b/output.json")"
[ "$(output "$ws3b" maxResponseTimeMs)" = "200" ] || fail "max: $(cat "$ws3b/output.json")"
grep -q '<b>190 ms</b><span>p95</span>' "$(output "$ws3b" report.path)/$(output "$ws3b" report.target)" \
  || fail "the page must show the same p95 the gate used"

# --- 6. markUnstable=false leaves the build result alone ---------------------------------------------------------
printf '{"pattern": "**/simulation.log", "markUnstable": "false"}' >"$ws3/input.json"
run "$ws3" >"$ws3/log.txt" 2>&1 || fail "markUnstable=false run failed"
[ "$(status "$ws3")" = "success" ] || fail "status: $(cat "$ws3/output.json")"
grep -q 'build.result' "$ws3/output.json" && fail "markUnstable=false must not request UNSTABLE"

# --- 6b. a clean run requests nothing: no UNSTABLE, no breach ------------------------------------------------
ws3c="$(mktemp -d)"; stats "$ws3c/js/global_stats.json" 250 250 0 80 40 60 120 200 900
printf '{}' >"$ws3c/input.json"
run "$ws3c" >"$ws3c/log.txt" 2>&1 || fail "clean run failed: $(cat "$ws3c/log.txt")"
[ "$(status "$ws3c")" = "success" ] || fail "status: $(cat "$ws3c/output.json")"
[ "$(output "$ws3c" koPercent)" = "0.0" ] || fail "koPercent: $(cat "$ws3c/output.json")"
grep -q 'build.result' "$ws3c/output.json" && fail "a clean run must not ask for UNSTABLE"
grep -q 'no threshold breached' "$ws3c/output.json" || fail "summary: $(cat "$ws3c/output.json")"

# --- 7. the same run present as both log and global_stats.json is counted once -----------------------------------
ws4="$(mktemp -d)"; mkdir -p "$ws4/report/js"
stats "$ws4/report/js/global_stats.json" 1000 990 10 120 50 90 300 600 4000
printf 'RUN\tsimulations.CheckoutSimulation\tcheckout-1\t1700000000000\tload test\t3.8.3\nREQUEST\t\tGET /cart\t1700000000000\t1700000000100\tOK\t \n' >"$ws4/report/simulation.log"
printf '{}' >"$ws4/input.json"
run "$ws4" >"$ws4/log.txt" 2>&1 || fail "dedupe run failed: $(cat "$ws4/log.txt")"
[ "$(output "$ws4" simulations)" = "1" ] || fail "the log beside global_stats.json is the same run: $(cat "$ws4/output.json")"
[ "$(output "$ws4" requests)" = "1000" ] || fail "double counted: $(cat "$ws4/output.json")"

# --- 8. a binary simulation.log is refused, not guessed at ------------------------------------------------------
ws5="$(mktemp -d)"; mkdir -p "$ws5/report"
python3 - "$ws5/report/simulation.log" <<'PY'
import sys
# first bytes of a real Gatling 3.12.1 log: record marker, version length, version string
open(sys.argv[1], "wb").write(b"\x00\x00\x00\x00\x063.12.1\x00\x00\x00\x00+computerdatabase.ComputerDatabaseSimulation")
PY
printf '{}' >"$ws5/input.json"
run "$ws5" >"$ws5/log.txt" 2>&1 && fail "a binary log must be refused"
grep -q '"errorCode":40004' "$ws5/output.json" || fail "expected 40004: $(cat "$ws5/output.json")"
grep -q 'binary simulation.log' "$ws5/output.json" || fail "the failure must name the format: $(cat "$ws5/output.json")"

# --- 9. bad input is named, and never reported as success -------------------------------------------------------
run "$ws5" >/dev/null 2>&1 # (throwaway)
ws6="$(mktemp -d)"; printf '{"pattern": "**/nothing.json"}' >"$ws6/input.json"
run "$ws6" >/dev/null 2>&1 && fail "no matching file must fail"
grep -q '"errorCode":40001' "$ws6/output.json" || fail "expected 40001: $(cat "$ws6/output.json")"

ws7="$(mktemp -d)"; printf '{"hello": "world"}' >"$ws7/global_stats.json"; printf '{}' >"$ws7/input.json"
run "$ws7" >/dev/null 2>&1 && fail "a json that is not a gatling report must fail"
grep -q '"errorCode":40002' "$ws7/output.json" || fail "expected 40002: $(cat "$ws7/output.json")"

ws8="$(mktemp -d)"; stats "$ws8/js/global_stats.json" 10 10 0 10 5 6 7 8 9
printf '{"maxFailurePercent": "abc"}' >"$ws8/input.json"
run "$ws8" >/dev/null 2>&1 && fail "a non-numeric threshold must be refused"
grep -q '"errorCode":40003' "$ws8/output.json" || fail "expected 40003: $(cat "$ws8/output.json")"
grep -q 'maxFailurePercent must be a number' "$ws8/output.json" || fail "the bad param must be named"
printf '{"maxMeanResponseTimeMs": "-5"}' >"$ws8/input.json"
run "$ws8" >/dev/null 2>&1 && fail "a negative threshold must be refused"
grep -q 'must not be negative' "$ws8/output.json" || fail "negative threshold: $(cat "$ws8/output.json")"
printf '{"pattern": "  "}' >"$ws8/input.json"
run "$ws8" >/dev/null 2>&1 && fail "an empty pattern must be refused"
grep -q '"errorCode":40005' "$ws8/output.json" || fail "expected 40005: $(cat "$ws8/output.json")"

# --- 10. output names may not collide with inputs, and no two differ only by case --------------------------------
python3 - task.json <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
inputs, outputs = doc["input"], doc["output"]
lower_in = {k.lower() for k in inputs}
lower_out = {k.lower() for k in outputs}
assert len(lower_out) == len(outputs), "two outputs differ only by case: %s" % sorted(outputs)
assert len(lower_in) == len(inputs), "two inputs differ only by case: %s" % sorted(inputs)
clash = lower_in & lower_out
assert not clash, "output names collide with inputs (the runner reuses the build environment): %s" % sorted(clash)
PY
[ $? -eq 0 ] || fail "output/input name hygiene: $(cat "$ws/output.json" 2>/dev/null)"

echo "smoke ok: global_stats.json counts+percentiles / threshold boundary / 5 named breaches / 2 sims aggregated"
echo "          + gatling assertions / text simulation.log fallback (nearest-rank p95) / log+stats dedupe"
echo "          + clean run quiet / binary log refused / 40001-40005 / output-name hygiene"
