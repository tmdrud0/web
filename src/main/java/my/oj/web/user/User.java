package my.oj.web.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String pass;

    private Long solvedCount = 0L;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "lastSolvedDate", column = @Column(name = "streak_last_solved_date")),
            @AttributeOverride(name = "currentStreak", column = @Column(name = "streak_current_streak")),
            @AttributeOverride(name = "longestStreak", column = @Column(name = "streak_longest_streak"))
    })
    private Streak streak = new Streak();


    private User(Long id, String name, String pass, Long solvedCount, Streak streak) {
        this.id = id;
        this.name = name;
        this.pass = pass;
        this.solvedCount = solvedCount;
        this.streak = streak != null ? streak : new Streak();
    }

    public static User create(String name, String pass) {
        return User.withState(null, name, pass, 0L, new Streak());
    }

    public static User withState(Long id, String name, String pass, Long solvedCount, Streak streak) {
        return new User(id, name, pass, solvedCount, streak);
    }

    public Long incSolvedCount(){
        return ++solvedCount;
    }

    public User(String name, String pass){
        this.name = name;
        this.pass = pass;
    }
}

