package my.oj.perf.liveimpact;

/**
 * The rows the live-impact summarizer reads. Every instant is epoch milliseconds in the container clock
 * frame - the frame the batch JVM, MySQL and Redis share - except a Gatling request, which is converted
 * into that frame by the reader with the measured Windows-to-container offset.
 */
final class LiveImpactRecords {

    private LiveImpactRecords() {
    }

    /** One live event the scoreboard accepted, from the batch role's {@code live-apply.csv}. */
    record LiveApply(long appliedAtMs, long offset, long submissionId, long judgedAtMs) {
    }

    /**
     * One judged result of the experiment contest, from MySQL: {@code COALESCE(final_judged_at,
     * provisional_judged_at)}, the definition the recovery pilot uses.
     *
     * @param seed whether the row was inserted by the seeder rather than judged under the load. Seeded
     *             rows reach the scoreboard through the pre-load rebuild, not the stream, so they are
     *             not part of the event set the backlog counts
     */
    record Judged(long submissionId, long judgedAtMs, boolean seed) {
    }

    /** One Gatling request, already shifted into the container frame. */
    record Request(String name, long startMs, long endMs, boolean ok) {
    }

    /** One tail-poller reading: how many of the lost set are back in the processed set. */
    record TailSample(long atMs, long present, long total) {
    }

    /** One row of the batch role's {@code recovery-trace.csv}. */
    record Recovery(String event, String thread, long startMs, long lockedAtMs, long endMs, int rows,
                    String detail, String outcome) {
    }
}
