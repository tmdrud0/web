package my.oj.web.contest.submission.queue;

import java.util.LinkedHashMap;
import java.util.Map;

public record ContestSubmissionBatchInsertResult(Map<Long, Resolution> resolutionsByReservedId) {

    public ContestSubmissionBatchInsertResult {
        resolutionsByReservedId = Map.copyOf(new LinkedHashMap<>(resolutionsByReservedId));
    }

    public Resolution resolutionFor(long reservedSubmissionId) {
        Resolution resolution = resolutionsByReservedId.get(reservedSubmissionId);
        if (resolution == null) {
            throw new IllegalStateException(
                    "Missing contest submission insert resolution for reserved id " + reservedSubmissionId
            );
        }
        return resolution;
    }

    public record Resolution(long submissionId, boolean duplicate) {

        public static Resolution inserted(long submissionId) {
            return new Resolution(submissionId, false);
        }

        public static Resolution duplicate(long submissionId) {
            return new Resolution(submissionId, true);
        }
    }
}
