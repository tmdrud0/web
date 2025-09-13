package my.oj.web.submission.guard;


import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserProblemGuardRepository
        extends JpaRepository<UserProblemGuard, UserProblemGuardId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from UserProblemGuard g where g.id.userId=:uid and g.id.problemId=:pid")
    Optional<UserProblemGuard> lock(@Param("uid") Long uid, @Param("pid") Long pid);

    @Modifying
    @Query(value = "INSERT IGNORE INTO user_problem_guard(user_id, problem_id) VALUES (?1, ?2)", nativeQuery = true)
    int insertIgnore(Long uid, Long pid);

    @Transactional(propagation = Propagation.MANDATORY)
    default Optional<UserProblemGuard> ensureLocked(Long uid, Long pid) {
        insertIgnore(uid, pid);
        return lock(uid, pid);
    }
}