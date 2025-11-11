package my.oj.web.problem;


import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import my.oj.web.contest.QContest;
import my.oj.web.problem.dto.ProblemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;


@RequiredArgsConstructor
public class ProblemRepositoryImpl implements ProblemRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ProblemDto> searchProblems(String problemName, Long problemId, Pageable pageable) {
        QProblem problem = QProblem.problem;
        QContest contest = QContest.contest;
        BooleanBuilder builder = new BooleanBuilder();

        if (problemName != null && !problemName.isBlank()) {
            builder.and(problem.name.containsIgnoreCase(problemName));
        }

        if (problemId != null) {
            builder.and(problem.id.eq(problemId));
        }

        List<ProblemDto> content = queryFactory
                .select(Projections.constructor(ProblemDto.class,
                        problem.id,
                        problem.name,
                        problem.contest.id,
                        problem.contest.name,
                        problem.contestNum
                ))
                .from(problem)
                .join(problem.contest, contest)
                .where(builder)
                .orderBy(problem.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();


        long total = Optional.ofNullable(
                queryFactory.select(problem.count())
                        .from(problem)
                        .where(builder)
                        .fetchOne()
        ).orElse(0L);


        return new PageImpl<>(content, pageable, total);
    }
}
