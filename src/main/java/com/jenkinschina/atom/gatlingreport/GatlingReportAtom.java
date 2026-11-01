package com.jenkinschina.atom.gatlingreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the files a Gatling simulation leaves behind and summarises them as one self-contained HTML page.
 *
 * <p>Two sources, because Gatling has changed its mind about what a report contains:
 *
 * <ul>
 *   <li>{@code global_stats.json} -- the summary Gatling wrote into the report directory (up to 3.13.4; removed in
 *       "stop generating unused files"). This is the file the Jenkins gatling plugin itself reads: its bytecode asks
 *       the workspace for every {@code global_stats.json} and hands it to Jackson as a {@code RequestReport}
 *       ({@code numberOfRequests{total,ok,ko}}, {@code meanResponseTime{total,ok,ko}}, {@code percentiles1..4},
 *       {@code meanNumberOfRequestsPerSecond}). It carries real percentiles, so it is preferred whenever present.
 *   <li>{@code simulation.log} -- the raw record log, still written by every version. This atom understands the
 *       tab separated form ({@code REQUEST\t<group>\t<name>\t<start>\t<end>\t<status>\t<message>}), which is what
 *       Gatling wrote through 3.8.x; newer versions switched to a binary record format this atom refuses to guess at.
 * </ul>
 *
 * <p>Percentiles are reported as Gatling's own four columns: {@code percentiles1..4} are the 50th, 75th, 95th and
 * 99th percentile (they increase monotonically in real reports, and 95/99 is what the charts label them).
 *
 * <p>Counting is a build gate, so the numbers this atom prints are the numbers it gated on: requests from
 * {@code numberOfRequests}, failures from {@code ko}, latency from {@code meanResponseTime.total} and the 95th/99th
 * percentiles. Nothing is inferred from the HTML.
 */
public class GatlingReportAtom implements AtomTask {

    private static final int MAX_FILES = 200;
    private static final int MAX_SAMPLES = 1_000_000;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/global_stats.json, **/simulation.log");
        String label = context.getParam("label", "Gatling");
        String outDir = context.getParam("outputDir", "out/gatling");
        if (pattern.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40005, "pattern is empty: name the report files to read, "
                    + "e.g. **/global_stats.json, **/simulation.log");
        }
        int maxRows = Math.max(1, context.getIntParam("maxRows", 200));
        boolean failOnThreshold = context.getBooleanParam("failOnThreshold", true);
        boolean failOnAssertionFailure = context.getBooleanParam("failOnAssertionFailure", true);
        boolean markUnstable = context.getBooleanParam("markUnstable", true);

        Limit maxFailureCount;
        Limit maxFailurePercent;
        Limit maxMean;
        Limit maxP95;
        Limit maxP99;
        try {
            maxFailureCount = Limit.parse("maxFailureCount", context.getParam("maxFailureCount", ""));
            maxFailurePercent = Limit.parse("maxFailurePercent", context.getParam("maxFailurePercent", ""));
            maxMean = Limit.parse("maxMeanResponseTimeMs", context.getParam("maxMeanResponseTimeMs", ""));
            maxP95 = Limit.parse("maxPercentile95Ms", context.getParam("maxPercentile95Ms", ""));
            maxP99 = Limit.parse("maxPercentile99Ms", context.getParam("maxPercentile99Ms", ""));
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40003, e.getMessage());
        }

        List<Path> files = match(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no gatling report file matched " + pattern
                    + " (looked for global_stats.json / simulation.log under " + workspace + ")");
        }

        List<Path> statsFiles = new ArrayList<>();
        List<Path> logFiles = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.equals("global_stats.json")) {
                statsFiles.add(file);
            } else if (name.equals("simulation.log")) {
                logFiles.add(file);
            }
        }

        List<Source> sources = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<Path> statsDirs = new LinkedHashSet<>();
        boolean binaryLogs = false;
        for (Path file : statsFiles) {
            Source source = readStats(file, workspace);
            if (source != null) {
                sources.add(source);
                statsDirs.add(file.getParent().toAbsolutePath().normalize());
            }
        }
        for (Path file : logFiles) {
            if (coveredByStats(file, statsDirs)) {
                // global_stats.json in the same report is the same run, with percentiles: do not count it twice
                continue;
            }
            Source source = readLog(file, workspace);
            if (source == null) {
                boolean binary = isBinaryLog(file);
                binaryLogs |= binary;
                String why = binary
                        ? "binary simulation.log (Gatling >= 3.9 writes records, not text) -- unsupported"
                        : "no REQUEST record found";
                skipped.add(rel(workspace, file) + ": " + why);
            } else {
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            if (binaryLogs) {
                return AtomResult.error(ErrorType.USER, 40004, "the only gatling report found has a binary "
                        + "simulation.log, which this atom does not decode: " + String.join("; ", skipped)
                        + ". Keep the report's global_stats.json (Gatling up to 3.13.4) in the workspace instead.");
            }
            return AtomResult.error(ErrorType.USER, 40002,
                    "matched " + files.size() + " file(s) but none is a gatling report: " + String.join("; ", skipped));
        }

        long requests = 0;
        long ok = 0;
        long ko = 0;
        long maxResponseTime = 0;
        long p95 = 0;
        long p99 = 0;
        double weightedMean = 0;
        int assertions = 0;
        int assertionsPassed = 0;
        int assertionsFailed = 0;
        List<Assertion> assertionRows = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (Source source : sources) {
            requests += source.total;
            ok += source.ok;
            ko += source.ko;
            maxResponseTime = Math.max(maxResponseTime, source.max);
            // percentiles cannot be averaged; across several simulations the worst one is what a gate must look at
            p95 = Math.max(p95, source.p95);
            p99 = Math.max(p99, source.p99);
            weightedMean += source.mean * source.total;
            rows.addAll(source.rows);
            assertions += source.assertions.size();
            for (Assertion assertion : source.assertions) {
                assertionRows.add(assertion);
                if (assertion.passed) {
                    assertionsPassed++;
                } else {
                    assertionsFailed++;
                }
            }
        }
        boolean sampled = sources.stream().anyMatch(source -> source.truncated);
        if (sampled) {
            context.log("[gatling] a simulation.log was longer than " + MAX_SAMPLES
                    + " responses; p95/p99 are taken from the first " + MAX_SAMPLES);
        }
        double mean = requests == 0 ? 0 : weightedMean / requests;
        double koPercent = requests == 0 ? 0 : ko * 100.0 / requests;

        List<String> breaches = new ArrayList<>();
        if (maxFailureCount.value != null && ko > maxFailureCount.value) {
            breaches.add("maxFailureCount breached: " + ko + " failed request(s) > " + fmt(maxFailureCount.value));
        }
        if (maxFailurePercent.value != null && koPercent > maxFailurePercent.value) {
            breaches.add("maxFailurePercent breached: " + fmtPct(koPercent) + "% > "
                    + fmt(maxFailurePercent.value) + "%");
        }
        if (maxMean.value != null && mean > maxMean.value) {
            breaches.add("maxMeanResponseTimeMs breached: " + fmt(mean) + " ms > " + fmt(maxMean.value) + " ms");
        }
        if (maxP95.value != null && p95 > maxP95.value) {
            breaches.add("maxPercentile95Ms breached: " + p95 + " ms > " + fmt(maxP95.value) + " ms");
        }
        if (maxP99.value != null && p99 > maxP99.value) {
            breaches.add("maxPercentile99Ms breached: " + p99 + " ms > " + fmt(maxP99.value) + " ms");
        }
        boolean assertionFailure = assertionsFailed > 0;
        if (assertionFailure && failOnAssertionFailure) {
            breaches.add("gatling assertion failed: " + assertionsFailed + " of " + assertions + " assertion(s)");
        }

        rows.sort((a, b) -> Long.compare(b.total, a.total));
        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Path html = dir.resolve("index.html");
        Files.writeString(html, render(label, pattern, sources, rows, maxRows, requests, ok, ko, mean, koPercent,
                p95, p99, maxResponseTime, assertions, assertionsPassed, assertionsFailed, assertionRows,
                breaches, maxFailureCount, maxFailurePercent, maxMean, maxP95, maxP99, skipped, sampled),
                StandardCharsets.UTF_8);

        context.log("[gatling] " + sources.size() + " simulation(s), " + requests + " request(s): " + ok
                + " ok, " + ko + " ko (" + fmtPct(koPercent) + "%), mean " + fmt(mean) + " ms, p95 " + p95 + " ms"
                + (assertions > 0 ? ", assertions " + assertionsPassed + "/" + assertions + " passed" : "")
                + " -> " + html);

        Summary summary = new Summary(sources.size(), requests, ok, ko, koPercent, mean, p95, p99, maxResponseTime,
                assertions, assertionsPassed, assertionsFailed, breaches);

        if (!breaches.isEmpty() && failOnThreshold) {
            // the report is written first on purpose: a gated build still gets the page that explains the gate
            AtomResult failed = AtomResult.failure("gatling thresholds not met: " + String.join("; ", breaches));
            summary.applyTo(failed);
            return failed.report("report", label, dir.toString(), "index.html");
        }

        AtomResult result = AtomResult.success();
        summary.applyTo(result);
        result.report("report", label, dir.toString(), "index.html");
        if (markUnstable && (ko > 0 || assertionsFailed > 0)) {
            // failures in a load test usually mean "look at this", not "the code is broken": UNSTABLE unless a
            // configured threshold turns them into a real failure above
            result.markUnstable().data("buildResult", "UNSTABLE");
        }
        return result;
    }

    // ------------------------------------------------------------------ sources

    /** {@code global_stats.json}: one aggregate object written by Gatling's GlobalStatsJsonTemplate. */
    private static Source readStats(Path file, Path workspace) throws IOException {
        Map<String, Object> root;
        try {
            root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, Object> counts = sub(root, "numberOfRequests");
        if (counts.isEmpty()) {
            return null;
        }
        long total = (long) stat(root, "numberOfRequests", "total");
        long ok = (long) stat(root, "numberOfRequests", "ok");
        long ko = (long) stat(root, "numberOfRequests", "ko");
        if (total < ok + ko) {
            total = ok + ko;
        }
        String name = text(root, "name");
        if (name.isEmpty()) {
            name = "All Requests";
        }
        double mean = stat(root, "meanResponseTime", "total");
        long max = (long) stat(root, "maxResponseTime", "total");
        Row row = new Row(name, total, ko, mean, max, rel(workspace, file));
        Source source = new Source(rel(workspace, file), reportName(file), "", false, total, total - ko, ko, mean,
                (long) stat(root, "percentiles3", "total"), (long) stat(root, "percentiles4", "total"), max, false,
                List.of(row), findAssertions(file.getParent()));
        return source;
    }

    /** The run folder Gatling created (the "js" subfolder is not interesting): a stand-in for the simulation name. */
    private static String reportName(Path file) {
        Path dir = file.getParent();
        if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equals("js")) {
            dir = dir.getParent();
        }
        return dir == null || dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    /**
     * {@code simulation.log}. Gatling wrote tab separated records up to 3.8.x:
     * <pre>
     * RUN     simulationClass  simulationId  start  description  version
     * USER    scenario         START|END     timestamp
     * REQUEST groupHierarchy   name          start  end    OK|KO  message
     * </pre>
     * The 2.x layout carries two extra leading fields (scenario, userId), so the fields are addressed from the right:
     * message, status, end, start, name -- which is the same order in both layouts.
     */
    private static Source readLog(Path file, Path workspace) throws IOException {
        if (isBinaryLog(file)) {
            return null;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String simulation = "";
        String version = "";
        Map<String, long[]> counters = new LinkedHashMap<>();      // name -> {total, ko, sumRt, max}
        List<Long> allResponseTimes = new ArrayList<>();
        boolean truncated = false;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            String kind = fields[0];
            if (kind.equals("RUN")) {
                if (fields.length > 1) {
                    simulation = fields[1];
                }
                if (fields.length > 5) {
                    version = fields[5];
                }
            } else if (kind.equals("REQUEST") && fields.length >= 7) {
                // 3.x: REQUEST <group> <name> <start> <end> <status> <message>
                // 2.x: REQUEST <scenario> <userId> <group> <name> <start> <end> <status> <message>
                // so the interesting fields are counted from the right, which both layouts agree on
                int last = fields.length - 1;
                String status = fields[last - 1];
                String name = fields[last - 4];
                if (name.isEmpty()) {
                    name = "(unnamed)";
                }
                long start;
                long end;
                try {
                    start = Long.parseLong(fields[last - 3].trim());
                    end = Long.parseLong(fields[last - 2].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                long responseTime = Math.max(0, end - start);
                long[] counter = counters.computeIfAbsent(name, key -> new long[] {0, 0, 0, 0});
                counter[0]++;
                if (status.equals("KO")) {
                    counter[1]++;
                }
                counter[2] += responseTime;
                counter[3] = Math.max(counter[3], responseTime);
                if (allResponseTimes.size() < MAX_SAMPLES) {
                    allResponseTimes.add(responseTime);
                } else {
                    truncated = true;
                }
            }
        }
        if (counters.isEmpty()) {
            return null;
        }
        long total = 0;
        long ko = 0;
        long max = 0;
        double mean = 0;
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : counters.entrySet()) {
            long[] counter = entry.getValue();
            total += counter[0];
            ko += counter[1];
            max = Math.max(max, counter[3]);
            mean += counter[2];
            rows.add(new Row(entry.getKey(), counter[0], counter[1],
                    counter[0] == 0 ? 0 : (double) counter[2] / counter[0], counter[3], rel(workspace, file)));
        }
        rows.sort((a, b) -> Long.compare(b.total, a.total));
        mean = total == 0 ? 0 : mean / total;
        // the percentiles a caller gates on are the ones over every response in this run, not the worst request's:
        // Gatling computes them the same way, so p95 here matches the p95 in Gatling's own report
        Collections.sort(allResponseTimes);
        return new Source(rel(workspace, file), simulation, version, true, total, total - ko, ko, mean,
                percentile(allResponseTimes, 95), percentile(allResponseTimes, 99), max, truncated, rows,
                findAssertions(file.getParent()));
    }

    /** Nearest-rank percentile over the response times kept for one request name. */
    private static long percentile(List<Long> sorted, double percent) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.min(sorted.size() - 1, Math.max(0, rank - 1)));
    }

    private static boolean isBinaryLog(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(512);
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when a global_stats.json already describes the run this log belongs to. */
    private static boolean coveredByStats(Path logFile, Set<Path> statsDirs) {
        Path reportDir = logFile.getParent().toAbsolutePath().normalize();
        for (Path dir : statsDirs) {
            if (dir.equals(reportDir) || reportDir.equals(dir.getParent())) {
                return true;
            }
        }
        return false;
    }

    /** {@code assertions.json} may sit beside global_stats.json, in its parent, or in a js/ folder. */
    private static List<Assertion> findAssertions(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        Path found = null;
        for (String candidate : new String[] {"assertions.json", "js/assertions.json", "../assertions.json",
                "../js/assertions.json"}) {
            Path path = dir.resolve(candidate).normalize();
            if (Files.isRegularFile(path)) {
                found = path;
                break;
            }
        }
        if (found == null) {
            return List.of();
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(Files.readString(found, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return List.of();
        }
        Object list = root.get("assertions");
        if (!(list instanceof List<?> entries)) {
            return List.of();
        }
        List<Assertion> assertions = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> assertion = (Map<String, Object>) map;
            assertions.add(new Assertion(
                    text(assertion, "path"),
                    text(assertion, "target"),
                    text(assertion, "condition"),
                    list(assertion, "expectedValues"),
                    list(assertion, "actualValue"),
                    Boolean.TRUE.equals(assertion.get("result")) || "true".equals(text(assertion, "result")),
                    text(assertion, "message")));
        }
        return assertions;
    }

    // ------------------------------------------------------------------ json helpers

    private static Map<String, Object> sub(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    /** Gatling writes numbers, but its non-raw mode writes them as quoted strings with thin spaces; accept both. */
    private static double stat(Map<String, Object> parent, String key, String field) {
        Object value = sub(parent, key).get(field);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String cleaned = String.valueOf(value).replaceAll("[^0-9.eE+-]", "");
        try {
            return cleaned.isEmpty() ? 0 : Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> items)) {
            return value == null ? "" : String.valueOf(value);
        }
        List<String> rendered = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Number number) {
                rendered.add(fmt(number.doubleValue()));
            } else {
                rendered.add(String.valueOf(item));
            }
        }
        return String.join(", ", rendered);
    }

    // ------------------------------------------------------------------ glob

    private static List<Path> match(Path workspace, String pattern) throws IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        if (matchers.isEmpty()) {
            throw new IllegalArgumentException("pattern is empty");
        }
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile).filter(path -> {
                Path relative = workspace.relativize(path);
                // "**/x.json" does not match a file at the workspace root; prefixing a segment makes it behave
                Path anchored = Path.of("_").resolve(relative);
                return matchers.stream().anyMatch(m -> m.matches(relative) || m.matches(anchored)
                        || m.matches(path.getFileName()));
            }).sorted().limit(MAX_FILES).forEach(files::add);
        }
        return files;
    }

    private static String rel(Path workspace, Path file) {
        Path relative = workspace.relativize(file);
        return relative.toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ rendering

    private static String render(String label, String pattern, List<Source> sources, List<Row> rows, int maxRows,
                                 long requests, long ok, long ko, double mean, double koPercent, long p95, long p99,
                                 long maxResponseTime, int assertions, int assertionsPassed, int assertionsFailed,
                                 List<Assertion> assertionRows, List<String> breaches, Limit maxFailureCount,
                                 Limit maxFailurePercent, Limit maxMean, Limit maxP95, Limit maxP99,
                                 List<String> skipped, boolean sampled) {
        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(label)).append("</title><style>")
                .append(":root{color-scheme:light}")
                .append("body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;margin:0;padding:28px;")
                .append("background:#f6f7f9;color:#111827}")
                .append("h1{font-size:22px;margin:0 0 4px}h2{font-size:15px;margin:26px 0 8px;color:#374151}")
                .append("p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:18px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:104px}")
                .append(".card b{display:block;font-size:21px;line-height:1.2}.card span{font-size:12px;color:#6b7280}")
                .append(".bad b{color:#b91c1c}.warn b{color:#b45309}.good b{color:#047857}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:7px 11px;border-bottom:1px solid #eef0f3;vertical-align:top}")
                .append("th{background:#fafbfc;color:#6b7280;font-weight:600}")
                .append("td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}")
                .append("code{background:#f3f4f6;padding:1px 5px;border-radius:4px;font-size:12px}")
                .append(".pass{color:#047857}.fail{color:#b91c1c;font-weight:600}")
                .append(".banner{background:#fef2f2;border:1px solid #fecaca;color:#991b1b;border-radius:10px;")
                .append("padding:12px 16px;margin-bottom:18px;font-size:13px}")
                .append(".banner.ok{background:#ecfdf5;border-color:#a7f3d0;color:#065f46}")
                .append("footer{color:#9ca3af;font-size:12px;margin-top:24px}")
                .append("</style></head><body>");

        html.append("<h1>").append(esc(label)).append("</h1><p class=\"meta\">")
                .append(sources.size()).append(" simulation(s) · <code>").append(esc(pattern)).append("</code> · ")
                .append("generated ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")))
                .append(sampled ? " · p95/p99 from the first 1,000,000 responses of a very long log" : "")
                .append("</p>");

        if (breaches.isEmpty()) {
            html.append("<div class=\"banner ok\">all gatling thresholds met</div>");
        } else {
            html.append("<div class=\"banner\"><b>thresholds breached</b><ul style=\"margin:6px 0 0 18px;padding:0\">");
            for (String breach : breaches) {
                html.append("<li>").append(esc(breach)).append("</li>");
            }
            html.append("</ul></div>");
        }

        html.append("<div class=\"cards\">");
        card(html, "requests", String.valueOf(requests), "");
        card(html, "ok", String.valueOf(ok), "good");
        card(html, "ko", String.valueOf(ko), ko > 0 ? "bad" : "");
        card(html, "ko %", fmtPct(koPercent) + "%", koPercent > 0 ? "warn" : "good");
        card(html, "mean", fmt(mean) + " ms", "");
        card(html, "p95", p95 + " ms", "");
        card(html, "p99", p99 + " ms", "");
        card(html, "max", maxResponseTime + " ms", "");
        if (assertions > 0) {
            card(html, "assertions", assertionsPassed + "/" + assertions,
                    assertionsFailed > 0 ? "bad" : "good");
        }
        html.append("</div>");

        // thresholds
        html.append("<h2>Thresholds</h2><table><tr><th>gate</th><th class=\"num\">limit</th>")
                .append("<th class=\"num\">actual</th><th>verdict</th></tr>");
        thresholdRow(html, "failed requests", maxFailureCount, ko);
        thresholdRow(html, "failed percent", maxFailurePercent, koPercent, "%", true);
        thresholdRow(html, "mean response time", maxMean, mean, " ms", false);
        thresholdRow(html, "p95 response time", maxP95, p95, " ms", false);
        thresholdRow(html, "p99 response time", maxP99, p99, " ms", false);
        if (assertionsFailed > 0 || assertions > 0) {
            html.append("<tr><td>gatling assertions</td><td class=\"num\">none failed</td><td class=\"num\">")
                    .append(assertionsFailed).append(" failed / ").append(assertions).append(" total</td><td class=\"")
                    .append(assertionsFailed > 0 ? "fail" : "pass").append("\">")
                    .append(assertionsFailed > 0 ? "BREACH" : "OK").append("</td></tr>");
        }
        html.append("</table>");

        // simulations
        html.append("<h2>Simulations</h2><table><tr><th>file</th><th>simulation</th><th>source</th>")
                .append("<th class=\"num\">requests</th><th class=\"num\">ok</th><th class=\"num\">ko</th>")
                .append("<th class=\"num\">mean</th><th class=\"num\">p95</th><th class=\"num\">max</th></tr>");
        for (Source source : sources) {
            html.append("<tr><td><code>").append(esc(source.file)).append("</code></td><td>")
                    .append(esc(source.simulation.isEmpty() ? "—" : source.simulation)).append("</td><td>")
                    .append(source.fromLog ? "simulation.log (text)" : "global_stats.json")
                    .append("</td><td class=\"num\">").append(source.total).append("</td><td class=\"num\">")
                    .append(source.ok).append("</td><td class=\"num\">").append(source.ko)
                    .append("</td><td class=\"num\">").append(fmt(source.mean)).append(" ms</td><td class=\"num\">")
                    .append(source.p95).append(" ms</td><td class=\"num\">").append(source.max)
                    .append(" ms</td></tr>");
        }
        html.append("</table>");

        // assertions
        if (!assertionRows.isEmpty()) {
            html.append("<h2>Assertions</h2><table><tr><th>result</th><th>path</th><th>target</th>")
                    .append("<th>condition</th><th class=\"num\">expected</th><th class=\"num\">actual</th>")
                    .append("<th>message</th></tr>");
            for (Assertion assertion : assertionRows) {
                html.append("<tr><td class=\"").append(assertion.passed ? "pass" : "fail").append("\">")
                        .append(assertion.passed ? "passed" : "FAILED").append("</td><td>")
                        .append(esc(assertion.path)).append("</td><td>").append(esc(assertion.target))
                        .append("</td><td>").append(esc(assertion.condition)).append("</td><td class=\"num\">")
                        .append(esc(assertion.expected)).append("</td><td class=\"num\">")
                        .append(esc(assertion.actual)).append("</td><td>").append(esc(assertion.message))
                        .append("</td></tr>");
            }
            html.append("</table>");
        }

        // requests
        html.append("<h2>Requests</h2>");
        int shown = Math.min(rows.size(), maxRows);
        html.append("<table><tr><th>request</th><th>source</th><th class=\"num\">requests</th>")
                .append("<th class=\"num\">ok</th><th class=\"num\">ko</th><th class=\"num\">mean</th>")
                .append("<th class=\"num\">max</th></tr>");
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(i);
            html.append("<tr><td>").append(esc(row.name))
                    .append("</td><td><code>").append(esc(row.source)).append("</code></td><td class=\"num\">")
                    .append(row.total).append("</td><td class=\"num\">").append(row.ok())
                    .append("</td><td class=\"num\">").append(row.ko).append("</td><td class=\"num\">")
                    .append(fmt(row.mean)).append(" ms</td><td class=\"num\">").append(row.max)
                    .append(" ms</td></tr>");
        }
        html.append("</table>");
        if (rows.size() > shown) {
            html.append("<p class=\"meta\">showing ").append(shown).append(" of ").append(rows.size())
                    .append(" request(s); raise maxRows to show more</p>");
        }

        if (!skipped.isEmpty()) {
            html.append("<h2>Skipped files</h2><ul class=\"meta\">");
            for (String note : skipped) {
                html.append("<li><code>").append(esc(note)).append("</code></li>");
            }
            html.append("</ul>");
        }

        html.append("<footer>gatling-report atom · counts are Gatling's own statistics, not re-derived from the HTML")
                .append(" · self-contained page, no external assets</footer>");
        return html.append("</body></html>").toString();
    }

    private static void card(StringBuilder html, String caption, String value, String css) {
        html.append("<div class=\"card ").append(css).append("\"><b>").append(esc(value))
                .append("</b><span>").append(esc(caption)).append("</span></div>");
    }

    private static void thresholdRow(StringBuilder html, String caption, Limit limit, double actual) {
        thresholdRow(html, caption, limit, actual, "", false);
    }

    private static void thresholdRow(StringBuilder html, String caption, Limit limit, double actual, String unit,
                                     boolean percent) {
        html.append("<tr><td>").append(esc(caption)).append("</td><td class=\"num\">")
                .append(limit.value == null ? "not set"
                        : (percent ? fmtPct(limit.value) : fmt(limit.value)) + unit)
                .append("</td><td class=\"num\">").append(percent ? fmtPct(actual) : fmt(actual)).append(unit)
                .append("</td><td class=\"");
        if (limit.value == null) {
            html.append("\">—");
        } else if (actual > limit.value) {
            html.append("fail\">BREACH");
        } else {
            html.append("pass\">OK");
        }
        html.append("</td></tr>");
    }

    private static String esc(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Integers print without a decimal point; everything else keeps one digit. No grouping: outputs stay greppable. */
    static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0000001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Percentages always keep one decimal, so 1% and 0.5% read like the same kind of number. */
    static String fmtPct(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // ------------------------------------------------------------------ model

    /** The numbers a build gates on; one place so the success and failure paths cannot drift apart. */
    private static final class Summary {
        private final int simulations;
        private final long requests;
        private final long ok;
        private final long ko;
        private final double koPercent;
        private final double mean;
        private final long p95;
        private final long p99;
        private final long max;
        private final int assertionTotal;
        private final int assertionPassed;
        private final int assertionFailed;
        private final List<String> breaches;

        Summary(int simulations, long requests, long ok, long ko, double koPercent, double mean, long p95, long p99,
                long max, int assertionTotal, int assertionPassed, int assertionFailed, List<String> breaches) {
            this.simulations = simulations;
            this.requests = requests;
            this.ok = ok;
            this.ko = ko;
            this.koPercent = koPercent;
            this.mean = mean;
            this.p95 = p95;
            this.p99 = p99;
            this.max = max;
            this.assertionTotal = assertionTotal;
            this.assertionPassed = assertionPassed;
            this.assertionFailed = assertionFailed;
            this.breaches = breaches;
        }

        void applyTo(AtomResult result) {
            result.data("simulations", String.valueOf(simulations))
                    .data("requests", String.valueOf(requests))
                    .data("okRequests", String.valueOf(ok))
                    .data("koRequests", String.valueOf(ko))
                    .data("koPercent", fmtPct(koPercent))
                    .data("meanResponseTimeMs", fmt(mean))
                    .data("p95ResponseTimeMs", String.valueOf(p95))
                    .data("p99ResponseTimeMs", String.valueOf(p99))
                    .data("maxResponseTimeMs", String.valueOf(max))
                    .data("assertionTotal", String.valueOf(assertionTotal))
                    .data("assertionPassed", String.valueOf(assertionPassed))
                    .data("assertionFailed", String.valueOf(assertionFailed))
                    .data("thresholdBreaches", String.valueOf(breaches.size()))
                    .data("thresholdSummary",
                            breaches.isEmpty() ? "no threshold breached" : String.join("; ", breaches));
        }
    }

    /** A gating number the caller configured; {@code null} means the gate is off. */
    private static final class Limit {
        final String name;
        final Double value;

        private Limit(String name, Double value) {
            this.name = name;
            this.value = value;
        }

        static Limit parse(String name, String raw) {
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                return new Limit(name, null);
            }
            double value;
            try {
                value = Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number, got \"" + trimmed + "\"");
            }
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative, got \"" + trimmed + "\"");
            }
            return new Limit(name, value);
        }
    }

    /** One row of the request table: per request name when a log was read, "All Requests" otherwise. */
    private static final class Row {
        final String name;
        final long total;
        final long ko;
        final double mean;
        final long max;
        final String source;

        Row(String name, long total, long ko, double mean, long max, String source) {
            this.name = name;
            this.total = total;
            this.ko = Math.min(ko, total);
            this.mean = mean;
            this.max = max;
            this.source = source;
        }

        long ok() {
            return total - ko;
        }
    }

    /**
     * One report file's contribution: the aggregates to gate on plus the detail rows for the page. Keeping the
     * percentiles here (and not on the rows) is what makes one simulation.log and one global_stats.json produce the
     * same p95 on the same run.
     */
    private static final class Source {
        final String file;
        final String simulation;
        final String version;
        final boolean fromLog;
        final long total;
        final long ok;
        final long ko;
        final double mean;
        final long p95;
        final long p99;
        final long max;
        final boolean truncated;
        final List<Row> rows;
        final List<Assertion> assertions;

        Source(String file, String simulation, String version, boolean fromLog, long total, long ok, long ko,
               double mean, long p95, long p99, long max, boolean truncated, List<Row> rows,
               List<Assertion> assertions) {
            this.file = file;
            this.simulation = simulation;
            this.version = version;
            this.fromLog = fromLog;
            this.total = total;
            this.ok = ok;
            this.ko = ko;
            this.mean = mean;
            this.p95 = p95;
            this.p99 = p99;
            this.max = max;
            this.truncated = truncated;
            this.rows = rows;
            this.assertions = assertions;
        }
    }

    private record Assertion(String path, String target, String condition, String expected, String actual,
                             boolean passed, String message) {
    }
}
