package my.oj.web.submission;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import my.oj.web.problem.QProblem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.dto.SubmissionSummaryDto;
import my.oj.web.user.QUser;
import my.oj.web.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class SubmissionRepositoryImpl implements SubmissionRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Slice<SubmissionSummaryDto> findSummaries(String username,
                                                     Long problemId,
                                                     Long lastId,
                                                     int pageSize,
                                                     SubmissionSortOrder order,
                                                     boolean acceptedOnly) {
        QSubmission submission = QSubmission.submission;
        QProblem problem = QProblem.problem;
        QUser user = QUser.user;

        BooleanBuilder where = new BooleanBuilder();

        if (lastId != null) {
            if (order == SubmissionSortOrder.ASC) {
                where.and(submission.id.gt(lastId));
            } else {
                where.and(submission.id.lt(lastId));
            }
        }

        if (username != null && !username.isBlank()) {
            User curUser = queryFactory
                    .selectFrom(QUser.user)
                    .where(QUser.user.name.eq(username))
                    .fetchOne();

            if (curUser == null) {
                return new SliceImpl<>(List.of(), PageRequest.of(0, pageSize), false);
            }

            where.and(submission.user.id.eq(curUser.getId()));
        }

        if (problemId != null) {
            where.and(submission.problem.id.eq(problemId));
        }

        if (acceptedOnly) {
            where.and(submission.result.eq(SubmissionResult.ACCEPTED));
        }

        OrderSpecifier<Long> orderSpecifier =
                order == SubmissionSortOrder.ASC ? submission.id.asc() : submission.id.desc();

        List<SubmissionSummaryDto> content = queryFactory
                .select(Projections.constructor(
                        SubmissionSummaryDto.class,
                        submission.id,
                        problem.id,
                        problem.name,
                        user.id,
                        user.name,
                        submission.result,
                        submission.submittedTime
                ))
                .from(submission)
                .join(submission.problem, problem)
                .join(submission.user, user)
                .where(where)
                .orderBy(orderSpecifier)
                .limit(pageSize + 1)
                .fetch();

        boolean hasNext = content.size() > pageSize;
        if (hasNext) {
            content.remove(pageSize);
        }

        return new SliceImpl<>(content, PageRequest.of(0, pageSize), hasNext);
    }
}
