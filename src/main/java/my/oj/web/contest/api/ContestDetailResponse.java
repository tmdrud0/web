package my.oj.web.contest.api;

import my.oj.web.contest.ContestStatus;
import my.oj.web.contest.ContestTimeline;
import my.oj.web.contest.dto.ContestDetailDto;

import java.time.LocalDateTime;

/**
 * A contest, without its problems and without its scoreboard.
 *
 * <p>Both of those are their own resources. The page bundled all three because it rendered a
 * header, a problem tab and a scoreboard tab from one handler, so a reader of the scoreboard paid
 * for the problem list and a reader of the problem list paid for the contest entity twice.
 */
public record ContestDetailResponse(long id,
                                    String name,
                                    LocalDateTime startTime,
                                    LocalDateTime endTime,
                                    ContestStatus status,
                                    String statusLabel,
                                    String timeMessage,
                                    boolean finalized) {

    public static ContestDetailResponse from(ContestDetailDto contest, LocalDateTime now) {
        ContestStatus status = ContestStatus.from(contest.startTime(), contest.endTime(), now);
        return new ContestDetailResponse(
                contest.id(),
                contest.name(),
                contest.startTime(),
                contest.endTime(),
                status,
                status.getLabel(),
                ContestTimeline.message(status, contest.startTime(), contest.endTime(), now),
                contest.finalized()
        );
    }
}
