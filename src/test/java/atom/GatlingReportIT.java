package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Gatling report atom, offline: the fixtures are the shapes Gatling writes, and the assertions are the numbers a
 * build would gate on. Everything {@code tools/smoke.sh} checked is here.
 */
class GatlingReportIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** A real Gatling 3.8.3 {@code global_stats.json} with the numbers replaced — the key layout is byte-for-byte. */
    private static String stats(int total, int ok, int ko, int mean, int p50, int p75, int p95, int p99, int max) {
        return """
                {
                    "name": "All Requests",
                    "numberOfRequests": {"total": %d, "ok": %d, "ko": %d},
                    "minResponseTime": {"total": 27, "ok": 27, "ko": 221},
                    "maxResponseTime": {"total": %d, "ok": %d, "ko": 273},
                    "meanResponseTime": {"total": %d, "ok": %d, "ko": 247},
                    "standardDeviation": {"total": 146, "ok": 146, "ko": 26},
                    "percentiles1": {"total": %d, "ok": %d, "ko": 247},
                    "percentiles2": {"total": %d, "ok": %d, "ko": 260},
                    "percentiles3": {"total": %d, "ok": %d, "ko": 270},
                    "percentiles4": {"total": %d, "ok": %d, "ko": 272},
                    "group1": {"name": "t < 800 ms", "count": %d, "percentage": 99},
                    "group2": {"name": "t ≥ 800 ms <br> t < 1200 ms", "count": 0, "percentage": 0},
                    "group3": {"name": "t ≥ 1200 ms", "count": 0, "percentage": 0},
                    "group4": {"name": "failed", "count": %d, "percentage": 1},
                    "meanNumberOfRequestsPerSecond": {"total": 19.107142857142858, "ok": 19.104910714285715, "ko": 0.002232142857142857}
                }
                """.formatted(total, ok, ko, max, max, mean, mean, p50, p50, p75, p75, p95, p95, p99, p99, ok, ko);
    }

    private static Atom twoSimulations(Atom atom) {
        return atom.file("a/js/global_stats.json", stats(1000, 990, 10, 120, 50, 90, 300, 600, 4000))
                .file("b/js/global_stats.json", stats(500, 480, 20, 200, 70, 120, 500, 900, 3000));
    }

    private static String page(AtomResult run) {
        return run.file(run.field("report.path") + "/" + run.field("report.target"));
    }

    @Test
    @DisplayName("global_stats.json gives the counts and percentiles, and a page that stands on its own")
    void readsGlobalStats() throws Exception {
        AtomResult run = atom.file("report-a/js/global_stats.json", stats(1000, 990, 10, 120, 50, 90, 300, 600, 4000))
                .input("{\"pattern\": \"**/global_stats.json\", \"label\": \"Checkout load\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("1", run.field("simulations"));
        assertEquals("1000", run.field("requests"));
        assertEquals("990", run.field("okRequests"));
        assertEquals("10", run.field("koRequests"));
        assertEquals("1.0", run.field("koPercent"));
        assertEquals("120", run.field("meanResponseTimeMs"));
        assertEquals("300", run.field("p95ResponseTimeMs"), "p95 comes from percentiles3");
        assertEquals("600", run.field("p99ResponseTimeMs"), "p99 comes from percentiles4");
        assertEquals("4000", run.field("maxResponseTimeMs"));
        assertEquals("0", run.field("assertionTotal"));
        assertEquals("0", run.field("thresholdBreaches"), "no threshold was set");

        String html = page(run);
        assertTrue(html != null, "the report target does not exist");
        assertTrue(html.contains("<h1>Checkout load</h1>"), "the label is not in the page");
        assertTrue(html.contains("<b>1000</b><span>requests</span>"), "requests card");
        assertTrue(html.contains("<b>990</b><span>ok</span>"), "ok card");
        assertTrue(html.contains("<b>10</b><span>ko</span>"), "ko card");
        assertTrue(html.contains("<b>300 ms</b><span>p95</span>"), "p95 card");
        assertTrue(html.contains("All Requests"), "the request name Gatling wrote is missing");
        assertTrue(html.contains("<title>Checkout load</title>"), "no title");
        assertFalse(html.contains("<script") || html.contains("http://") || html.contains("https://")
                || html.contains("src="), "the page must not reference anything external");
        assertTrue(html.contains("</body></html>"), "the page is not closed");
    }

    @Test
    @DisplayName("thresholds equal to the actual values do not fire: a limit is a maximum")
    void honoursTheBoundary() throws Exception {
        AtomResult run = atom.file("js/global_stats.json", stats(1000, 990, 10, 120, 50, 90, 300, 600, 4000))
                .input("{\"pattern\": \"**/global_stats.json\", \"label\": \"B\", \"maxFailureCount\": \"10\", "
                        + "\"maxFailurePercent\": \"1.0\", \"maxMeanResponseTimeMs\": \"120\", "
                        + "\"maxPercentile95Ms\": \"300\", \"maxPercentile99Ms\": \"600\"}")
                .run();

        run.assertOk();
        assertEquals("0", run.field("thresholdBreaches"), "breaches at the boundary");
        assertTrue(run.file("output.json").contains("no threshold breached"), "summary");
        String html = page(run);
        assertTrue(html.contains("<span>thresholds met</span>") || html.contains("all gatling thresholds met"),
                "the page must say the gate passed");
    }

    @Test
    @DisplayName("every exceeded gate is named, and the page is still written")
    void namesEveryBreach() throws Exception {
        AtomResult run = atom.file("js/global_stats.json", stats(1000, 990, 10, 120, 50, 90, 300, 600, 4000))
                .input("{\"pattern\": \"**/global_stats.json\", \"label\": \"B\", \"maxFailureCount\": \"9\", "
                        + "\"maxFailurePercent\": \"0.5\", \"maxMeanResponseTimeMs\": \"119\", "
                        + "\"maxPercentile95Ms\": \"299\", \"maxPercentile99Ms\": \"599\"}")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status());
        assertEquals("5", run.field("thresholdBreaches"));
        for (String gate : List.of("maxFailureCount", "maxFailurePercent", "maxMeanResponseTimeMs",
                "maxPercentile95Ms", "maxPercentile99Ms")) {
            assertTrue(run.file("output.json").contains(gate + " breached"), gate + " must be named");
        }
        assertTrue(run.file("output.json").contains("maxFailurePercent breached: 1.0% > 0.5%"),
                "the percent breach must show both numbers");
        assertTrue(run.file("output.json").contains("maxPercentile95Ms breached: 300 ms > 299 ms"),
                "the p95 breach must show both numbers");

        String html = page(run);
        assertTrue(html != null, "a failed gate must still publish the page that explains it");
        assertEquals(5, count(html, "BREACH"), "the page must mark 5 breaches");
        assertTrue(html.contains("thresholds breached"), "no breach banner in the page");
    }

    @Test
    @DisplayName("failOnThreshold=false turns the same breaches into UNSTABLE instead of a failure")
    void unstableInsteadOfFailed() throws Exception {
        AtomResult run = atom.file("js/global_stats.json", stats(1000, 990, 10, 120, 50, 90, 300, 600, 4000))
                .input("{\"pattern\": \"**/global_stats.json\", \"label\": \"B\", \"maxFailurePercent\": \"0.5\", "
                        + "\"failOnThreshold\": \"false\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("1", run.field("thresholdBreaches"), "the breach must still be counted");
        assertTrue(run.file("output.json").contains("\"build.result\":{\"type\":\"string\",\"value\":\"UNSTABLE\"}"),
                "a breach that is not failing the step must ask for UNSTABLE");
    }

    @Test
    @DisplayName("two simulations are summed, weighted and worst-case, and gatling assertions are read")
    void aggregatesTwoSimulations() throws Exception {
        String assertions = """
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
                """;
        AtomResult run = twoSimulations(atom)
                .file("b/js/assertions.json", assertions)
                .input("{\"pattern\": \"**/global_stats.json\", \"label\": \"Two sims\", "
                        + "\"failOnAssertionFailure\": \"false\", \"markUnstable\": \"false\"}")
                .run();

        run.assertOk();
        assertEquals("2", run.field("simulations"));
        assertEquals("1500", run.field("requests"), "requests must be summed");
        assertEquals("1470", run.field("okRequests"));
        assertEquals("30", run.field("koRequests"));
        assertEquals("2.0", run.field("koPercent"));
        assertEquals("146.7", run.field("meanResponseTimeMs"), "mean must be request-weighted");
        assertEquals("500", run.field("p95ResponseTimeMs"), "p95 must be the worst simulation");
        assertEquals("900", run.field("p99ResponseTimeMs"));
        assertEquals("3", run.field("assertionTotal"));
        assertEquals("2", run.field("assertionPassed"));
        assertEquals("1", run.field("assertionFailed"));

        String html = page(run);
        assertTrue(html.contains("percent of failed requests is 2.00% but should be less than 1%"),
                "assertion message missing");
        assertTrue(html.contains("<td class=\"fail\">FAILED</td>"), "the failed assertion must be marked");
        assertTrue(html.contains("<b>2/3</b><span>assertions</span>"), "assertion card");
        assertTrue(html.contains("assertion"), "no assertion table");
    }

    @Test
    @DisplayName("a failed gatling assertion fails the step by default")
    void failsOnAssertionByDefault() throws Exception {
        AtomResult run = twoSimulations(atom)
                .file("b/js/assertions.json", """
                        {"simulation": "simulations.Checkout", "assertions": [
                          {"path": "Global", "target": "percent of failed requests", "condition": "is less than",
                           "expectedValues": [1], "result": false, "message": "2.00% but should be less than 1%",
                           "actualValue": [2]}]}
                        """)
                .input("{\"pattern\": \"**/global_stats.json\", \"label\": \"Two sims\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("gatling assertion failed: 1 of 1 assertion(s)"),
                run.file("output.json"));
        assertEquals("1", run.field("thresholdBreaches"));
    }

    @Test
    @DisplayName("a text simulation.log is read when there is no global_stats.json")
    void fallsBackToTheTextLog() throws Exception {
        AtomResult run = atom.file("checkoutsimulation-20240102030405/simulation.log",
                        "RUN\tsimulations.CheckoutSimulation\tcheckout-20240102030405\t1700000000000\tload test\t3.8.3\n"
                                + "USER\tCheckout\tSTART\t1700000000100\n"
                                + "REQUEST\t\tGET /cart\t1700000000100\t1700000000200\tOK\t \n"
                                + "REQUEST\tcart\tGET /cart\t1700000000200\t1700000000400\tOK\t \n"
                                + "REQUEST\t\tPOST /checkout\t1700000000400\t1700000000700\tOK\t \n"
                                + "REQUEST\t\tPOST /checkout\t1700000000700\t1700000001100\tKO\tstatus.find.is(200), but actually found 500\n")
                .input("{\"pattern\": \"**/simulation.log\", \"label\": \"From the log\"}")
                .run();

        run.assertOk();
        assertEquals("4", run.field("requests"));
        assertEquals("3", run.field("okRequests"));
        assertEquals("1", run.field("koRequests"));
        assertEquals("25.0", run.field("koPercent"));
        assertEquals("250", run.field("meanResponseTimeMs"), "(100+200+300+400)/4");
        assertEquals("400", run.field("p95ResponseTimeMs"));
        assertEquals("400", run.field("maxResponseTimeMs"));
        assertTrue(run.file("output.json").contains("\"build.result\":{\"type\":\"string\",\"value\":\"UNSTABLE\"}"),
                "failures without a threshold must ask for UNSTABLE");

        String html = page(run);
        assertTrue(html.contains("POST /checkout"), "per request row missing from the log report");
        assertTrue(html.contains("GET /cart"), "per request row missing from the log report");
        assertTrue(html.contains("simulation.log (text)"), "the page must say where the numbers came from");
    }

    @Test
    @DisplayName("p95 is a nearest-rank percentile, not the maximum")
    void computesThePercentile() throws Exception {
        StringBuilder log = new StringBuilder(
                "RUN\tsimulations.PercentileCheck\tpercentile-check\t1700000000000\tp95 check\t3.8.3\n");
        for (int i = 1; i <= 20; i++) {
            long start = 1700000000000L + i * 1000L;
            log.append("REQUEST\t\tGET /item\t").append(start).append('\t').append(start + i * 10L)
                    .append("\tOK\t \n");
        }
        AtomResult run = atom.file("run/simulation.log", log.toString())
                .input("{\"pattern\": \"**/simulation.log\", \"label\": \"Percentiles\"}")
                .run();

        run.assertOk();
        assertEquals("20", run.field("requests"));
        assertEquals("105", run.field("meanResponseTimeMs"), "mean of 10..200");
        assertEquals("190", run.field("p95ResponseTimeMs"), "nearest-rank p95 over 20 samples");
        assertEquals("200", run.field("p99ResponseTimeMs"));
        assertEquals("200", run.field("maxResponseTimeMs"));
        assertTrue(page(run).contains("<b>190 ms</b><span>p95</span>"),
                "the page must show the same p95 the gate used");
    }

    @Test
    @DisplayName("markUnstable=false leaves the build result alone")
    void doesNotMarkUnstableWhenAskedNotTo() throws Exception {
        AtomResult run = atom.file("run/simulation.log",
                        "RUN\tsimulations.CheckoutSimulation\tcheckout-1\t1700000000000\tload test\t3.8.3\n"
                                + "REQUEST\t\tGET /cart\t1700000000000\t1700000000100\tKO\tnope\n")
                .input("{\"pattern\": \"**/simulation.log\", \"markUnstable\": \"false\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertFalse(run.file("output.json").contains("build.result"),
                "markUnstable=false must not request UNSTABLE");
    }

    @Test
    @DisplayName("a clean run asks for nothing: no UNSTABLE, no breach")
    void staysQuietWhenClean() throws Exception {
        AtomResult run = atom.file("js/global_stats.json", stats(250, 250, 0, 80, 40, 60, 120, 200, 900))
                .input("{}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("0.0", run.field("koPercent"));
        assertFalse(run.file("output.json").contains("build.result"), "a clean run must not ask for UNSTABLE");
        assertTrue(run.file("output.json").contains("no threshold breached"), "summary");
    }

    @Test
    @DisplayName("the same run present as both log and global_stats.json is counted once")
    void deduplicatesTheSameRun() throws Exception {
        AtomResult run = atom.file("report/js/global_stats.json", stats(1000, 990, 10, 120, 50, 90, 300, 600, 4000))
                .file("report/simulation.log",
                        "RUN\tsimulations.CheckoutSimulation\tcheckout-1\t1700000000000\tload test\t3.8.3\n"
                                + "REQUEST\t\tGET /cart\t1700000000000\t1700000000100\tOK\t \n")
                .input("{}")
                .run();

        run.assertOk();
        assertEquals("1", run.field("simulations"), "the log beside global_stats.json is the same run");
        assertEquals("1000", run.field("requests"), "double counted");
    }

    @Test
    @DisplayName("a binary simulation.log is refused, not guessed at")
    void refusesABinaryLog() throws Exception {
        AtomResult run = atom.fileBytes("report/simulation.log", new byte[] {
                0, 0, 0, 0, 6, '3', '.', '1', '2', '.', '1', 0, 0, 0, 0, '+',
                'c', 'o', 'm', 'p', 'u', 't', 'e', 'r', 'd', 'a', 't', 'a', 'b', 'a', 's', 'e' })
                .input("{}")
                .run();

        run.assertRefused();
        assertEquals("40004", run.field("errorCode"));
        assertTrue(run.field("message").contains("binary simulation.log"), "the failure must name the format");
    }

    @Test
    @DisplayName("bad input is named, and never reported as success")
    void namesBadInput() throws Exception {
        AtomResult nothing = atom.input("{\"pattern\": \"**/nothing.json\"}").run();
        nothing.assertRefused();
        assertEquals("40001", nothing.field("errorCode"));

        AtomResult notGatling = atom.file("global_stats.json", "{\"hello\": \"world\"}")
                .input("{}").run();
        notGatling.assertRefused();
        assertEquals("40002", notGatling.field("errorCode"));

        Atom atomWithStats = atom.file("js/global_stats.json", stats(10, 10, 0, 10, 5, 6, 7, 8, 9));
        AtomResult notANumber = atomWithStats.input("{\"maxFailurePercent\": \"abc\"}").run();
        notANumber.assertRefused();
        assertEquals("40003", notANumber.field("errorCode"));
        assertTrue(notANumber.field("message").contains("maxFailurePercent must be a number"),
                "the bad param must be named");

        AtomResult negative = atomWithStats.input("{\"maxMeanResponseTimeMs\": \"-5\"}").run();
        negative.assertRefused();
        assertTrue(negative.field("message").contains("must not be negative"), "negative threshold");

        AtomResult blank = atomWithStats.input("{\"pattern\": \"  \"}").run();
        blank.assertRefused();
        assertEquals("40005", blank.field("errorCode"));
    }

    @Test
    @DisplayName("no output name shadows an input, and none differs from another only by case")
    void taskJsonIsSane() throws Exception {
        Path module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        Map<String, Object> spec = Json.parseObject(Files.readString(module.resolve("task.json"), StandardCharsets.UTF_8));
        Map<?, ?> inputs = (Map<?, ?>) spec.get("input");
        Map<?, ?> outputs = (Map<?, ?>) spec.get("output");

        Map<String, String> lowerIn = lowerKeys(inputs);
        Map<String, String> lowerOut = lowerKeys(outputs);
        assertEquals(outputs.size(), lowerOut.size(), "two outputs differ only by case");
        assertEquals(inputs.size(), lowerIn.size(), "two inputs differ only by case");

        for (String name : lowerOut.keySet()) {
            assertFalse(lowerIn.containsKey(name), "output names collide with an input: " + name);
        }
    }

    private static Map<String, String> lowerKeys(Map<?, ?> source) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Object key : source.keySet()) {
            keys.put(String.valueOf(key).toLowerCase(), String.valueOf(key));
        }
        return keys;
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            found++;
            at = text.indexOf(needle, at + needle.length());
        }
        return found;
    }
}
