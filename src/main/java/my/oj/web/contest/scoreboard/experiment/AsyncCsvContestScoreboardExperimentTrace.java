package my.oj.web.contest.scoreboard.experiment;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the experiment trace as two CSV files from one background thread.
 *
 * <h2>Why asynchronous</h2>
 *
 * <p>The live path this observes is the thing being measured, so the only work done on the caller's
 * thread is building a small record and one non-blocking {@link BlockingQueue#offer}. Formatting and
 * file I/O happen on the writer thread. When the queue is full the record is dropped and counted rather
 * than waited for, and the counts are written to {@code trace-status.properties} so a run whose trace is
 * incomplete says so instead of reading as a pipeline that applied less.</p>
 *
 * <h2>Files</h2>
 *
 * <ul>
 *   <li>{@code live-apply.csv}: one row per applied live event -
 *       {@code appliedAtEpochMs,offset,submissionId,judgedAt,judgedAtEpochMsUtc,batchSize}.
 *       {@code judgedAt} is the message's zone-less value as sent; {@code judgedAtEpochMsUtc} reads it
 *       as UTC, which is the zone the experiment containers run in.</li>
 *   <li>{@code recovery-trace.csv}: one row per recovery record -
 *       {@code event,thread,startEpochMs,lockedAtEpochMs,endEpochMs,rows,detail,outcome}.</li>
 * </ul>
 *
 * <p>Both files are appended to, and a header is written only to a new file, so a JVM restart inside
 * one run continues the same files rather than truncating what the first JVM recorded.</p>
 */
@Slf4j
public class AsyncCsvContestScoreboardExperimentTrace implements ContestScoreboardExperimentTrace, AutoCloseable {

    static final String LIVE_FILE = "live-apply.csv";
    static final String RECOVERY_FILE = "recovery-trace.csv";
    static final String STATUS_FILE = "trace-status.properties";
    static final String LIVE_HEADER = "appliedAtEpochMs,offset,submissionId,judgedAt,judgedAtEpochMsUtc,batchSize";
    static final String RECOVERY_HEADER = "event,thread,startEpochMs,lockedAtEpochMs,endEpochMs,rows,detail,outcome";

    private record LiveBatch(long appliedAtEpochMillis, List<LiveEvent> events) {
    }

    private final Path directory;
    private final BlockingQueue<Object> queue;
    private final long flushIntervalNanos;
    private final AtomicLong droppedLiveBatches = new AtomicLong();
    private final AtomicLong droppedLiveEvents = new AtomicLong();
    private final AtomicLong droppedRecoveryRecords = new AtomicLong();
    private long writtenLiveEvents;
    private long writtenRecoveryRecords;
    private long writeFailures;
    private String lastStatus = "";
    private final Writer liveWriter;
    private final Writer recoveryWriter;
    private final Thread writerThread;
    private volatile boolean closing;

    public AsyncCsvContestScoreboardExperimentTrace(Path directory, int queueCapacity, Duration flushInterval) {
        this(directory, queueCapacity, flushInterval, true);
    }

    /**
     * @param startWriter false leaves draining to {@link #drain()}, which is how a test decides exactly
     *                    when records reach the files
     */
    AsyncCsvContestScoreboardExperimentTrace(Path directory, int queueCapacity, Duration flushInterval,
                                             boolean startWriter) {
        this.directory = directory;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.flushIntervalNanos = Math.max(1L, flushInterval.toNanos());
        try {
            Files.createDirectories(directory);
            this.liveWriter = open(directory.resolve(LIVE_FILE), LIVE_HEADER);
            this.recoveryWriter = open(directory.resolve(RECOVERY_FILE), RECOVERY_HEADER);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open the scoreboard experiment trace in " + directory, e);
        }
        if (startWriter) {
            writerThread = new Thread(this::runWriter, "scoreboard-experiment-trace");
            writerThread.setDaemon(true);
            writerThread.start();
        } else {
            writerThread = null;
        }
        log.warn("Scoreboard experiment trace is ON and writing to {}; this is an experiment setting, "
                + "not a production one", directory);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void liveBatchApplied(long appliedAtEpochMillis, List<LiveEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        if (closing || !queue.offer(new LiveBatch(appliedAtEpochMillis, events))) {
            droppedLiveBatches.incrementAndGet();
            droppedLiveEvents.addAndGet(events.size());
        }
    }

    @Override
    public void recovery(RecoveryRecord record) {
        if (record == null) {
            return;
        }
        if (closing || !queue.offer(record)) {
            droppedRecoveryRecords.incrementAndGet();
        }
    }

    /** Writes everything queued so far and flushes both files. Called by the writer thread and by tests. */
    synchronized void drain() {
        List<Object> pending = new ArrayList<>();
        queue.drainTo(pending);
        try {
            for (Object item : pending) {
                if (item instanceof LiveBatch batch) {
                    writeLive(batch);
                } else if (item instanceof RecoveryRecord record) {
                    writeRecovery(record);
                }
            }
            liveWriter.flush();
            recoveryWriter.flush();
            writeStatus();
        } catch (IOException e) {
            // Never thrown to anyone: the writer thread has no caller, and the paths being measured must
            // not learn about the instrument. The failure count in the status file is what tells the run
            // the trace is short - written on the next drain that can write at all.
            writeFailures++;
            log.error("Scoreboard experiment trace could not write to {}", directory, e);
        }
    }

    @Override
    public void close() {
        closing = true;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drain();
        try {
            liveWriter.close();
            recoveryWriter.close();
        } catch (IOException e) {
            log.error("Scoreboard experiment trace could not close its files in {}", directory, e);
        }
    }

    long droppedLiveEvents() {
        return droppedLiveEvents.get();
    }

    long droppedRecoveryRecords() {
        return droppedRecoveryRecords.get();
    }

    private void runWriter() {
        while (!closing) {
            try {
                TimeUnit.NANOSECONDS.sleep(flushIntervalNanos);
            } catch (InterruptedException e) {
                break;
            }
            drain();
        }
    }

    private void writeLive(LiveBatch batch) throws IOException {
        int size = batch.events().size();
        for (LiveEvent event : batch.events()) {
            liveWriter.append(Long.toString(batch.appliedAtEpochMillis())).append(',')
                    .append(Long.toString(event.offset())).append(',')
                    .append(Long.toString(event.submissionId())).append(',');
            if (event.judgedAt() != null) {
                liveWriter.append(event.judgedAt().toString()).append(',')
                        .append(Long.toString(event.judgedAt().toInstant(ZoneOffset.UTC).toEpochMilli()));
            } else {
                liveWriter.append(',');
            }
            liveWriter.append(',').append(Integer.toString(size)).append('\n');
            writtenLiveEvents++;
        }
    }

    private void writeRecovery(RecoveryRecord record) throws IOException {
        recoveryWriter.append(record.event().name()).append(',')
                .append(csv(record.thread())).append(',')
                .append(Long.toString(record.startEpochMillis())).append(',')
                .append(Long.toString(record.lockedAtEpochMillis())).append(',')
                .append(Long.toString(record.endEpochMillis())).append(',')
                .append(Integer.toString(record.rows())).append(',')
                .append(csv(record.detail())).append(',')
                .append(csv(record.outcome())).append('\n');
        writtenRecoveryRecords++;
    }

    private void writeStatus() throws IOException {
        String status = "writtenLiveEvents=" + writtenLiveEvents + "\n"
                + "writtenRecoveryRecords=" + writtenRecoveryRecords + "\n"
                + "droppedLiveBatches=" + droppedLiveBatches.get() + "\n"
                + "droppedLiveEvents=" + droppedLiveEvents.get() + "\n"
                + "droppedRecoveryRecords=" + droppedRecoveryRecords.get() + "\n"
                + "writeFailures=" + writeFailures + "\n";
        if (status.equals(lastStatus)) {
            return;
        }
        Path target = directory.resolve(STATUS_FILE);
        Path temporary = directory.resolve(STATUS_FILE + ".tmp");
        Files.writeString(temporary, status, StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        lastStatus = status;
    }

    private static Writer open(Path file, String header) throws IOException {
        boolean fresh = !Files.exists(file) || Files.size(file) == 0L;
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            writer.append(header).append('\n');
        }
        return writer;
    }

    static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
