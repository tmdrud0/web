package my.oj.web.contest;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestSummaryView;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.problem.dto.ContestProblemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Contest metadata.
 *
 * <p>The detail and the problem list are separate calls because they are separate requests. The
 * page had one handler behind a tabbed view, so it loaded whatever any tab might want: the contest
 * projection, the problem list, and then the contest entity a second time for one boolean. Three
 * round trips on every read of a scoreboard that needed none of them.
 */
@Service
@RequiredArgsConstructor
public class ContestService {

    private final ContestRepository contestRepository;
    private final ProblemRepository problemRepository;

    @Transactional(readOnly = true)
    public ContestDetailDto getDetail(long contestId) {
        return contestRepository.findDetailById(contestId)
                .orElseThrow(() -> new ContestNotFoundException(contestId));
    }

    @Transactional(readOnly = true)
    public List<ContestProblemDto> getProblems(long contestId) {
        if (!contestRepository.existsById(contestId)) {
            throw new ContestNotFoundException(contestId);
        }
        return problemRepository.findDtoByContestId(contestId);
    }

    @Transactional(readOnly = true)
    public Page<ContestSummaryView> getSummaries(Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        return contestRepository.findAll(pageable).map(contest -> toSummary(contest, now));
    }

    private ContestSummaryView toSummary(Contest contest, LocalDateTime now) {
        ContestStatus status = ContestStatus.from(contest.getStartTime(), contest.getEndTime(), now);
        return new ContestSummaryView(
                contest.getId(),
                contest.getName(),
                contest.getStartTime(),
                contest.getEndTime(),
                status,
                status.getLabel(),
                ContestTimeline.message(status, contest.getStartTime(), contest.getEndTime(), now)
        );
    }
}
