package my.oj.web.submission.api;

import my.oj.web.api.SliceResponse;
import my.oj.web.submission.SubmissionNotFoundException;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionSortOrder;
import my.oj.web.submission.dto.SubmissionSummaryDto;
import my.oj.web.submission.dto.SubmissionViewProjection;
import org.springframework.data.domain.Slice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading submissions.
 *
 * <p>Writing one is {@code POST /api/problems/{id}/submissions}, which lives with the contest
 * submission pipeline because that is what it feeds. Splitting the read from the write keeps this
 * a plain repository query and leaves the write path as the only thing that has to be fast.
 */
@RestController
class SubmissionApiController {

    private static final int MAX_PAGE_SIZE = 200;

    private final SubmissionRepository submissionRepository;

    SubmissionApiController(SubmissionRepository submissionRepository) {
        this.submissionRepository = submissionRepository;
    }

    /**
     * Keyset paging: {@code lastId} is the id of the last row the caller saw. The page handler
     * bound this through a custom editor that swallowed unparseable ids into nulls; a JSON caller
     * gets a 400 instead, which is what a bad cursor is.
     */
    @GetMapping("/api/submissions")
    SliceResponse<SubmissionSummaryDto> submissions(@RequestParam(required = false, defaultValue = "") String user,
                                                    @RequestParam(required = false) Long problemId,
                                                    @RequestParam(required = false) Long lastId,
                                                    @RequestParam(defaultValue = "100") int size,
                                                    @RequestParam(defaultValue = "desc") String order,
                                                    @RequestParam(defaultValue = "false") boolean acceptedOnly) {
        int effectiveSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Slice<SubmissionSummaryDto> slice = submissionRepository.findSummaries(
                user, problemId, lastId, effectiveSize, SubmissionSortOrder.from(order), acceptedOnly
        );
        return SliceResponse.of(slice);
    }

    @GetMapping("/api/submissions/{submissionId}")
    SubmissionViewProjection submission(@PathVariable long submissionId) {
        return submissionRepository.findViewById(submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
    }
}
