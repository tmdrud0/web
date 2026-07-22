package my.oj.web.submission.event.guard;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface UserGuardRepository extends JpaRepository<UserGuard, Long> {

    @Modifying
    @Query(value = "INSERT IGNORE INTO user_guard(user_id) VALUES (:userId)", nativeQuery = true)
    int insertIgnore(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from UserGuard g where g.userId = :userId")
    UserGuard lockForUpdate(@Param("userId") Long userId);

    @Transactional(propagation = Propagation.MANDATORY)
    default void guard(Long userId) {
        insertIgnore(userId);
        lockForUpdate(userId);
    }
}

