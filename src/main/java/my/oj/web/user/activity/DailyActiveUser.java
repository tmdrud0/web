package my.oj.web.user.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "daily_active_users")
@IdClass(DailyActiveUser.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyActiveUser {

    @Id
    private LocalDate day;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_active_time", nullable = false)
    private LocalDateTime lastActiveTime;

    public DailyActiveUser(LocalDate day, Long userId, LocalDateTime lastActiveTime) {
        this.day = day;
        this.userId = userId;
        this.lastActiveTime = lastActiveTime;
    }

    public static class Pk implements Serializable {
        private LocalDate day;
        private Long userId;

        public Pk() {}

        public Pk(LocalDate day, Long userId) {
            this.day = day;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pk pk = (Pk) o;
            return Objects.equals(day, pk.day) && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, userId);
        }
    }
}
