package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactAnalysis.Result;
import my.oj.perf.liveimpact.LiveImpactAnalysis.RunEvents;
import my.oj.perf.liveimpact.LiveImpactAnalysis.Second;
import my.oj.perf.liveimpact.LiveImpactAnalysis.Thresholds;
import my.oj.perf.liveimpact.LiveImpactRecords.Request;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Summarizes one live-impact run directory.
 *
 * <pre>
 * java -cp gatling/build/classes/java/main my.oj.perf.liveimpact.LiveImpactSummarizer &lt;runDir&gt; [options]
 * </pre>
 *
 * <p>Reads, from {@code runDir}: {@code run-events.properties} (required), {@code trace/live-apply.csv},
 * {@code trace/recovery-trace.csv}, {@code trace/trace-status.properties}, {@code judged.csv},
 * {@code tail-poll.csv} and the first {@code simulation.log} under {@code gatling/}. Writes
 * {@code live-impact-timeseries.csv}, {@code live-impact-summary.csv} and {@code live-impact-summary.md}
 * next to them.</p>
 *
 * <p>Options: {@code --stall-seconds N}, {@code --throughput-tolerance X}, {@code --backlog-tolerance-seconds X},
 * {@code --latency-tolerance X}, {@code --min-during-seconds N}, {@code --submit-request NAME}.</p>
 */
public final class LiveImpactSummarizer {

    static final String DEFAULT_SUBMIT_REQUEST = "api-contest-submit";

    private LiveImpactSummarizer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: LiveImpactSummarizer <runDir> [--stall-seconds N] [--throughput-tolerance X] "
                    + "[--backlog-tolerance-seconds X] [--latency-tolerance X] [--min-during-seconds N] "
                    + "[--submit-request NAME]");
            System.exit(64);
        }
        Path runDir = Path.of(args[0]);
        Map<String, String> options = options(args);
        if (options.getOrDefault("--phase", "run").equals("calibration")) {
            Map<String, String> metrics = summarizeCalibration(runDir,
                    options.getOrDefault("--submit-request", DEFAULT_SUBMIT_REQUEST));
            System.out.println("calibration=" + metrics.get("calibration.verdict")
                    + " (" + metrics.get("calibration.reasons") + ")");
            return;
        }
        Thresholds defaults = Thresholds.defaults();
        Thresholds thresholds = new Thresholds(
                Integer.parseInt(options.getOrDefault("--stall-seconds", Integer.toString(defaults.stallSeconds()))),
                Double.parseDouble(options.getOrDefault("--throughput-tolerance", Double.toString(defaults.throughputTolerance()))),
                Double.parseDouble(options.getOrDefault("--backlog-tolerance-seconds", Double.toString(defaults.backlogToleranceSeconds()))),
                Double.parseDouble(options.getOrDefault("--latency-tolerance", Double.toString(defaults.latencyTolerance()))),
                Integer.parseInt(options.getOrDefault("--min-during-seconds", Integer.toString(defaults.minDuringSeconds()))));
        Result result = summarize(runDir, thresholds, options.getOrDefault("--submit-request", DEFAULT_SUBMIT_REQUEST));
        System.out.println("verdict=" + result.verdict() + " (" + result.metrics().get("verdictReasons") + ")");
    }

    static Result summarize(Path runDir, Thresholds thresholds, String submitRequest) throws IOException {
        Map<String, String> events;
        try (Reader reader = LiveImpactReader.open(runDir.resolve("run-events.properties"))) {
            events = LiveImpactReader.properties(reader);
        }
        RunEvents run = new RunEvents(
                required(events, "measureFromMs"),
                required(events, "snapshotAtMs"),
                required(events, "rollbackAtMs"),
                required(events, "faultAtMs"),
                required(events, "measureToMs"),
                required(events, "preRollbackOffset"));
        long gatlingOffset = Long.parseLong(events.getOrDefault("gatlingClockOffsetMs", "0"));

        Path trace = runDir.resolve("trace");
        List<LiveImpactRecords.LiveApply> live;
        try (Reader reader = LiveImpactReader.open(trace.resolve("live-apply.csv"))) {
            live = LiveImpactReader.liveApplies(reader);
        }
        List<LiveImpactRecords.Recovery> recovery;
        try (Reader reader = LiveImpactReader.open(trace.resolve("recovery-trace.csv"))) {
            recovery = LiveImpactReader.recoveryRows(reader);
        }
        Map<String, String> traceStatus;
        try (Reader reader = LiveImpactReader.open(trace.resolve("trace-status.properties"))) {
            traceStatus = LiveImpactReader.properties(reader);
        }
        List<LiveImpactRecords.Judged> judged;
        try (Reader reader = LiveImpactReader.open(runDir.resolve("judged.csv"))) {
            judged = LiveImpactReader.judged(reader);
        }
        List<LiveImpactRecords.TailSample> tail;
        try (Reader reader = LiveImpactReader.open(runDir.resolve("tail-poll.csv"))) {
            tail = LiveImpactReader.tailSamples(reader);
        }
        Path simulationLog = findSimulationLog(runDir.resolve("gatling"));
        List<Request> requests;
        try (Reader reader = LiveImpactReader.open(simulationLog)) {
            requests = LiveImpactReader.gatlingRequests(reader, submitRequest, gatlingOffset);
        }

        Result result = LiveImpactAnalysis.analyze(run, thresholds, live, judged, requests, tail, recovery);
        Map<String, String> metrics = new LinkedHashMap<>(result.metrics());
        metrics.putAll(ingress(requests, run));
        metrics.put("traceComplete", Boolean.toString(traceComplete(traceStatus, live.isEmpty())));
        metrics.putAll(observation(metrics, run, events.get("requiredObservationSeconds")));
        traceStatus.forEach((key, value) -> metrics.put("trace." + key, value));
        metrics.put("simulationLog", simulationLog == null ? LiveImpactAnalysis.UNAVAILABLE : runDir.relativize(simulationLog).toString());
        // The runner's own record - conditions, pause lengths, clock offset, counters - is carried into
        // the summary unchanged, so the verdict and the conditions it was reached under are one file.
        events.forEach((key, value) -> metrics.putIfAbsent("run." + key, value));

        writeTimeseries(runDir.resolve("live-impact-timeseries.csv"), result.seconds());
        writeSummary(runDir.resolve("live-impact-summary.csv"), metrics);
        writeMarkdown(runDir.resolve("live-impact-summary.md"), metrics);
        return new Result(metrics, result.seconds());
    }

    /**
     * The calibration phase: a steady window with nothing injected. Reads {@code calibration-events.properties}
     * ({@code measureFromMs}, {@code measureToMs}, {@code targetRps}, {@code gatlingClockOffsetMs}) and writes
     * {@code live-impact-calibration.csv}.
     */
    static Map<String, String> summarizeCalibration(Path runDir, String submitRequest) throws IOException {
        Map<String, String> events;
        try (Reader reader = LiveImpactReader.open(runDir.resolve("calibration-events.properties"))) {
            events = LiveImpactReader.properties(reader);
        }
        long from = required(events, "measureFromMs");
        long to = required(events, "measureToMs");
        double targetRps = Double.parseDouble(events.getOrDefault("targetRps", "0"));
        long gatlingOffset = Long.parseLong(events.getOrDefault("gatlingClockOffsetMs", "0"));
        List<LiveImpactRecords.LiveApply> live;
        try (Reader reader = LiveImpactReader.open(runDir.resolve("trace").resolve("live-apply.csv"))) {
            live = LiveImpactReader.liveApplies(reader);
        }
        List<LiveImpactRecords.Judged> judged;
        try (Reader reader = LiveImpactReader.open(runDir.resolve("judged.csv"))) {
            judged = LiveImpactReader.judged(reader);
        }
        List<Request> requests;
        try (Reader reader = LiveImpactReader.open(findSimulationLog(runDir.resolve("gatling")))) {
            requests = LiveImpactReader.gatlingRequests(reader, submitRequest, gatlingOffset);
        }
        Map<String, String> metrics = new LinkedHashMap<>(LiveImpactCalibration.analyze(
                from, to, targetRps, LiveImpactCalibration.Thresholds.defaults(), live, judged, requests));
        events.forEach((key, value) -> metrics.putIfAbsent("run." + key, value));
        writeSummary(runDir.resolve("live-impact-calibration.csv"), metrics);
        return metrics;
    }

    /**
     * Whether the load ran long enough after the recovery for the "after" window to mean anything: the
     * plan asks for at least {@code requiredObservationSeconds} of it.
     */
    static Map<String, String> observation(Map<String, String> metrics, RunEvents run, String requiredSeconds) {
        Map<String, String> observation = new LinkedHashMap<>();
        boolean recovered = Boolean.parseBoolean(metrics.get("recoveredWithinMeasurement"));
        if (!recovered) {
            observation.put("observedAfterRecoverySeconds", LiveImpactAnalysis.UNAVAILABLE);
            observation.put("observationSufficient", "false");
            return observation;
        }
        long observed = (run.measureToMs() - Long.parseLong(metrics.get("T_recovered"))) / 1000L;
        long required = requiredSeconds == null || requiredSeconds.isBlank() ? 0L : Long.parseLong(requiredSeconds.trim());
        observation.put("observedAfterRecoverySeconds", Long.toString(observed));
        observation.put("observationSufficient", Boolean.toString(observed >= required));
        return observation;
    }

    /** OK/KO totals and the ingress p95 over the measured window. */
    static Map<String, String> ingress(List<Request> requests, RunEvents run) {
        Map<String, String> metrics = new LinkedHashMap<>();
        long ok = 0L;
        long ko = 0L;
        List<Long> durations = new ArrayList<>();
        for (Request request : requests) {
            if (request.endMs() < run.measureFromMs() || request.endMs() > run.measureToMs()) {
                continue;
            }
            if (request.ok()) {
                ok++;
            } else {
                ko++;
            }
            durations.add(request.endMs() - request.startMs());
        }
        metrics.put("gatling.submitOk", Long.toString(ok));
        metrics.put("gatling.submitKo", Long.toString(ko));
        Long p95 = LiveImpactAnalysis.percentile(durations, 95);
        metrics.put("gatling.ingressP95Ms", p95 == null ? LiveImpactAnalysis.UNAVAILABLE : Long.toString(p95));
        return metrics;
    }

    static boolean traceComplete(Map<String, String> status, boolean noLiveRows) {
        if (status.isEmpty() || noLiveRows) {
            return false;
        }
        for (String key : List.of("droppedLiveBatches", "droppedLiveEvents", "droppedRecoveryRecords", "writeFailures")) {
            if (!status.getOrDefault(key, "0").equals("0")) {
                return false;
            }
        }
        return true;
    }

    private static long required(Map<String, String> events, String key) {
        String value = events.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("run-events.properties has no '" + key + "'");
        }
        return Long.parseLong(value.trim());
    }

    private static Path findSimulationLog(Path gatlingDir) throws IOException {
        if (!Files.isDirectory(gatlingDir)) {
            return null;
        }
        try (var paths = Files.walk(gatlingDir)) {
            return paths.filter(path -> path.getFileName().toString().equals("simulation.log"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void writeTimeseries(Path path, List<Second> seconds) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("epochSecond,secondsFromFault,phase,submitOk,submitKo,judged,firstApplied,newApplied,"
                    + "reconsumed,backlog,tailPresent\n");
            for (Second second : seconds) {
                writer.write(second.epochSecond() + "," + second.secondsFromFault() + "," + second.phase() + ","
                        + second.submitOk() + "," + second.submitKo() + "," + second.judged() + ","
                        + second.firstApplied() + "," + second.newApplied() + "," + second.reconsumed() + ","
                        + second.backlog() + "," + second.tailPresent() + "\n");
            }
        }
    }

    private static void writeSummary(Path path, Map<String, String> metrics) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("metric,value\n");
            for (Map.Entry<String, String> entry : metrics.entrySet()) {
                writer.write(csv(entry.getKey()) + "," + csv(entry.getValue()) + "\n");
            }
        }
    }

    private static void writeMarkdown(Path path, Map<String, String> metrics) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# Live-impact run summary\n\n");
            writer.write("Verdict: **" + metrics.get("verdict") + "** - " + metrics.get("verdictReasons") + "\n\n");
            writer.write("| metric | value |\n|---|---|\n");
            for (Map.Entry<String, String> entry : metrics.entrySet()) {
                writer.write("| " + entry.getKey() + " | " + entry.getValue().replace("|", "\\|") + " |\n");
            }
        }
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Options are '--name value' pairs; read '" + args[i] + "'");
            }
            options.put(args[i], args[++i]);
        }
        return options;
    }
}
