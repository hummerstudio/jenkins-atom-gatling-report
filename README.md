# gatling-report atom

Reads what a Gatling simulation left behind, counts the successes and failures, checks the thresholds a build should
gate on, and publishes one self-contained HTML page on the build page.

```
atomCode    gatling-report
version     1.0.0
runtime     JDK 21 (java), implements com.jenkinschina.atom.sdk.AtomTask
```

## What it reads

Gatling has changed what a report contains, so the atom understands two files and prefers the richer one:

| file | written by | what the atom uses it for |
| --- | --- | --- |
| `global_stats.json` | Gatling up to 3.13.4 (removed in `711b8d4 "stop generating unused files"`) | every number: `numberOfRequests{total,ok,ko}`, `meanResponseTime`, `percentiles1..4`, `maxResponseTime` |
| `simulation.log` (**text**) | Gatling up to 3.8.x | counts and response times, parsed from the `REQUEST` records |

If both exist for the same run, the log is skipped — `global_stats.json` is the same run with real percentiles, and
counting it twice would double the report.

`global_stats.json` is the file the Jenkins gatling plugin itself reads, so an existing job already produces it in the
place this atom looks.

### Evidence for the formats

Nothing here was written from memory; each shape was read out of the real thing:

1. **`global_stats.json` is what a Jenkins build normally has** — downloaded `https://updates.jenkins.io/latest/gatling.hpi`
   (plugin 1.3.0), unpacked `WEB-INF/lib/gatling-1.3.0.jar`, and read the bytecode strings:
   `io/gatling/jenkins/SimulationReport.class` contains the literal `**/global_stats.json` and
   `locateStatsFile()`; `SimulationReport$1 extends TypeReference<RequestReport>`; `RequestReport` declares
   `numberOfRequests`, `meanResponseTime`, `percentiles1..4`, `meanNumberOfRequestsPerSecond`, each typed
   `Statistics` (`getTotal/getOK/getKO`) or `ResponseTimeGroup` (`getPercentage`).
2. **The exact JSON key layout** — `gatling-charts/src/main/scala/io/gatling/charts/template/GlobalStatsJsonTemplate.scala`
   at tag `v2.3.1`: `name`, `numberOfRequests{total,ok,ko}`, `min/max/mean/standardDeviation/percentiles1..4` each
   `{total,ok,ko}`, `group1..4{name,count,percentage}`, `meanNumberOfRequestsPerSecond{total,ok,ko}`.
3. **A real report, byte for byte** — `Bahmni/performance-test` commit `ea052a6`, folder
   `baseline_report-20220923072056541_40users_15mins_768mb/` (Gatling 3.8.3): `js/global_stats.json` reports
   `total 17120 / ok 17118 / ko 2`, `percentiles3 313`, `percentiles4 617`, `maxResponseTime 3889`. The smoke's
   fixture is this file with the numbers replaced; the key layout, nesting and the `"t ≥ 800 ms <br> t < 1200 ms"`
   group labels are kept as they are.
4. **`assertions.json`** — `AssertionsJsonTemplate.scala` at `v2.3.1`:
   `{path, target, condition, expectedValues[], result, message, actualValue[]}` inside
   `{simulation, simulationId, start, description, scenarios[], assertions[]}`. The real 3.8.3 report above has an
   empty `assertions` array, so the assertion *rows* in the smoke are shaped from the template, not copied from a
   third-party report.
5. **The text `simulation.log`** — the same 3.8.3 report's `simulation.log` (1 MB, 17 120 `REQUEST` lines) starts
   `REQUEST\t\tget login locations\t1663917663188\t1663917663371\tOK\t `; every one of its lines has exactly 7
   tab-separated fields, and its `RUN` line is `<class> <runId> <start> <description> <version>`.
6. **Percentiles are p50/p75/p95/p99** — `percentiles1..4` increase monotonically in the real file
   (61 → 107 → 313 → 617), which is consistent with the four columns Gatling's own stats table labels
   50th/75th/95th/99th percentile.
7. **Binary logs are the new normal** — `gatling-charts/src/test/resources/known_stats/simulation.log` in Gatling's
   own repo (3.12.1) is a binary record stream, and issue `gatling/gatling#4615` states `global_stats.json` stopped
   being generated in 3.13.5.

### Cross-check done while building this

Feeding the real 3.8.3 `simulation.log` (no `global_stats.json` involved) produced
`17120 requests, 17118 ok, 2 ko, mean 104.9 ms, p95 313 ms, p99 617 ms, max 3889 ms` — the same numbers the real
`global_stats.json` in that report carries (mean 105, p95 313, p99 617, max 3889). That is why the percentiles are
computed over every response in a run, not per request name: per-name percentiles would have reported the worst
endpoint's p95 (3104 ms) as the run's p95 and disagreed with Gatling's own page.

## Inputs

| input | default | meaning |
| --- | --- | --- |
| `pattern` | `**/global_stats.json, **/simulation.log` | globs (comma separated) of the files to read |
| `label` | `Gatling` | the report title |
| `outputDir` | `out/gatling` | where `index.html` goes, relative to the workspace |
| `maxFailureCount` | *(off)* | fail when `ko` exceeds this; `0` means "not a single failed request" |
| `maxFailurePercent` | *(off)* | fail when `ko / total * 100` exceeds this |
| `maxMeanResponseTimeMs` | *(off)* | fail when the request-weighted mean exceeds this |
| `maxPercentile95Ms` | *(off)* | fail when p95 exceeds this |
| `maxPercentile99Ms` | *(off)* | fail when p99 exceeds this |
| `failOnThreshold` | `true` | a breach fails the step; `false` only marks it |
| `failOnAssertionFailure` | `true` | a failed Gatling assertion fails the step |
| `markUnstable` | `true` | failures without a breach request `UNSTABLE` instead of succeeding silently |
| `maxRows` | `200` | request rows in the page |

A limit is a maximum: `actual == limit` passes, `actual > limit` breaches.

## Outputs

`simulations`, `requests`, `okRequests`, `koRequests`, `koPercent`, `meanResponseTimeMs`, `p95ResponseTimeMs`,
`p99ResponseTimeMs`, `maxResponseTimeMs`, `assertionTotal`, `assertionPassed`, `assertionFailed`, `thresholdBreaches`,
`thresholdSummary`, `report`, `buildResult` (plus the runner's `build.result` key when `UNSTABLE` is requested).

None of these names collides with an input, and no two differ only by case — the runner merges the build environment
into the next step's inputs, so an output called `label` would pin the next step's label. `tools/smoke.sh` asserts
that invariant against `task.json`.

## Errors

| code | meaning |
| --- | --- |
| 40001 | nothing matched `pattern` |
| 40002 | the matched files are not Gatling statistics (no `numberOfRequests`, no `REQUEST` record) |
| 40003 | a threshold is not a number, or is negative |
| 40004 | the only report found has a binary `simulation.log` (Gatling ≥ 3.9), which this atom does not decode |
| 40005 | `pattern` is empty |

## Assumptions and limits

- **Binary `simulation.log` is refused, not guessed.** From 3.9-ish Gatling writes length-prefixed binary records
  (see the 3.12.1 fixture); decoding them would mean tracking an undocumented, version-dependent layout. Keep
  `global_stats.json` in the workspace (Gatling ≤ 3.13.4 still writes it) or run a version that writes the text log.
  The failure says exactly this instead of reporting zero requests.
- **Aggregation over several simulations**: request counts and failures are summed; the mean is weighted by each
  simulation's request count; p95/p99/max are the worst simulation's, because percentiles cannot be averaged.
- **`global_stats.json` carries one aggregate object per run** ("All Requests"), so its page shows a single row; the
  per-request table only fills up when the numbers come from a log.
- **Gatling's group ranges (`group1..4`) are not used for gating** — they were rendered as `t < 800 ms` style labels
  with `<br>` in them and their thresholds are configurable, so the atom recomputes nothing from them.
- **Assertion rows** follow `AssertionsJsonTemplate` (evidence above) but no real report with a non-empty
  `assertions` array was available here; only `result`, `path`, `target`, `condition`, `message`, `expectedValues`,
  `actualValue` are read, and an unknown extra key is ignored.
- **Percentile labels**: `percentiles1..4` are read as p50/p75/p95/p99 (see evidence 6). If a project reconfigures
  `charting.indicators.percentile1/2`, `maxPercentile95Ms`/`maxPercentile99Ms` will follow the configured columns,
  not the literal 95/99 — the gate then means "Gatling's third/fourth percentile column".

## Build and test

```bash
export JAVA_HOME=~/tools/jdk-21.0.12.1+1/Contents/Home
./build.sh          # dist/gatling-report/gatling-report.jar (+ task.json, meta.json)
./tools/smoke.sh    # offline: fixtures for both report shapes, thresholds, assertions, error codes
```

The smoke makes no network calls: every fixture is written into a throwaway workspace, and every case asserts the
numbers a build would gate on (plus that the page it wrote contains them).
