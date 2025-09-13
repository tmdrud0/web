package my.oj.web.problem;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.oj.web.contest.Contest;

@Entity
@Table(name = "problem")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id")
    private Contest contest;

    private Long contestNum;


    private Problem(Long id, String name, Contest contest, Long contestNum) {
        this.id = id;
        this.name = name;
        this.contest = contest;
        this.contestNum = contestNum;
    }

    public static Problem create(String name, Contest contest, Long contestNum) {
        return new Problem(null, name, contest, contestNum);
    }

}