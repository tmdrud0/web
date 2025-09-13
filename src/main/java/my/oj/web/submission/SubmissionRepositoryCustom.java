package my.oj.web.submission;

import my.oj.web.submission.dto.SubmissionSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface SubmissionRepositoryCustom {
    Slice<SubmissionSummaryDto> findSummaries(String username, Long problemId, Long lastId, int pageSize);
    }