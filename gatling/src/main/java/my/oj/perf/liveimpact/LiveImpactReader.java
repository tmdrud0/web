package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactRecords.Judged;
import my.oj.perf.liveimpact.LiveImpactRecords.LiveApply;
import my.oj.perf.liveimpact.LiveImpactRecords.Recovery;
import my.oj.perf.liveimpact.LiveImpactRecords.Request;
import my.oj.perf.liveimpact.LiveImpactRecords.TailSample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the files one live-impact run leaves in its artifact directory.
 *
 * <p>Every reader checks the header it expects, so a file from a different harness version fails here
 * rather than being read column-shifted.</p>
 */
final class LiveImpactReader {

    private LiveImpactReader() {
    }

    static List<LiveApply> liveApplies(Reader source) throws IOException {
        List<LiveApply> rows = new ArrayList<>();
        for (List<String> fields : csv(source, "appliedAtEpochMs,offset,submissionId,judgedAt,judgedAtEpochMsUtc,batchSize")) {
            rows.add(new LiveApply(
                    Long.parseLong(fields.get(0)),
                    Long.parseLong(fields.get(1)),
                    Long.parseLong(fields.get(2)),
                    fields.get(4).isEmpty() ? -1L : Long.parseLong(fields.get(4))));
        }
        return rows;
    }

    static List<Judged> judged(Reader source) throws IOException {
        List<Judged> rows = new ArrayList<>();
        for (List<String> fields : csv(source, "submissionId,judgedAtEpochMs,seed")) {
            if (fields.get(1).isEmpty()) {
                // Never judged: not a result yet, so it is on neither side of the backlog.
                continue;
            }
            rows.add(new Judged(
                    Long.parseLong(fields.get(0)),
                    Long.parseLong(fields.get(1)),
                    fields.get(2).equals("1") || fields.get(2).equalsIgnoreCase("true")));
        }
        return rows;
    }

    static List<TailSample> tailSamples(Reader source) throws IOException {
        List<TailSample> rows = new ArrayList<>();
        for (List<String> fields : csv(source, "atEpochMicros,present,total")) {
            rows.add(new TailSample(
                    Math.floorDiv(Long.parseLong(fields.get(0)), 1000L),
                    Long.parseLong(fields.get(1)),
                    Long.parseLong(fields.get(2))));
        }
        return rows;
    }

    static List<Recovery> recoveryRows(Reader source) throws IOException {
        List<Recovery> rows = new ArrayList<>();
        for (List<String> fields : csv(source, "event,thread,startEpochMs,lockedAtEpochMs,endEpochMs,rows,detail,outcome")) {
            rows.add(new Recovery(
                    fields.get(0),
                    fields.get(1),
                    Long.parseLong(fields.get(2)),
                    Long.parseLong(fields.get(3)),
                    Long.parseLong(fields.get(4)),
                    Integer.parseInt(fields.get(5)),
                    fields.get(6),
                    fields.get(7)));
        }
        return rows;
    }

    /**
     * Requests from a Gatling 3.10 text {@code simulation.log}, shifted into the container frame.
     *
     * <p>The record is {@code REQUEST, groups, name, start, end, OK|KO, message}, tab-separated. The
     * status is located rather than indexed, with the two timestamps immediately before it and the name
     * before those, so a group column that is present or absent does not shift the read.</p>
     *
     * @param requestName   only requests of this name are returned
     * @param clockOffsetMs added to Gatling's Windows-clock timestamps to reach the container frame
     */
    static List<Request> gatlingRequests(Reader source, String requestName, long clockOffsetMs) throws IOException {
        List<Request> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("REQUEST\t")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                for (int i = 4; i < fields.length; i++) {
                    if ((fields[i].equals("OK") || fields[i].equals("KO"))
                            && isLong(fields[i - 1]) && isLong(fields[i - 2])) {
                        String name = fields[i - 3];
                        if (name.equals(requestName)) {
                            rows.add(new Request(name,
                                    Long.parseLong(fields[i - 2]) + clockOffsetMs,
                                    Long.parseLong(fields[i - 1]) + clockOffsetMs,
                                    fields[i].equals("OK")));
                        }
                        break;
                    }
                }
            }
        }
        return rows;
    }

    static Map<String, String> properties(Reader source) throws IOException {
        Properties properties = new Properties();
        properties.load(source);
        Map<String, String> values = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted().forEach(key -> values.put(key, properties.getProperty(key)));
        return values;
    }

    static Reader open(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return new StringReader("");
        }
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    /**
     * Rows after the header, with RFC 4180 quoting. An empty source is no rows; a source whose header is
     * not the expected one is refused.
     */
    static List<List<String>> csv(Reader source, String expectedHeader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String header = reader.readLine();
            if (header == null) {
                return rows;
            }
            if (!header.replace(String.valueOf((char) 0xFEFF), "").trim().equals(expectedHeader)) {
                throw new IllegalArgumentException("Expected header '" + expectedHeader + "' but read '" + header + "'");
            }
            String line;
            int expected = expectedHeader.split(",", -1).length;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> fields = split(line);
                if (fields.size() != expected) {
                    throw new IllegalArgumentException("Expected " + expected + " fields but read " + fields.size()
                            + " in '" + line + "'");
                }
                rows.add(fields);
            }
        }
        return rows;
    }

    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static boolean isLong(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
