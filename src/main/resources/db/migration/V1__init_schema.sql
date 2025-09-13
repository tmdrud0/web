-- Flyway V1: Consolidated initial schema
-- MySQL 8+, InnoDB, utf8mb4

SET NAMES utf8mb4;

CREATE TABLE `contest` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255),
    `start_time` DATETIME,
    `end_time` DATETIME,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255),
    `pass` VARCHAR(255),
    `solved_count` BIGINT NOT NULL DEFAULT 0,
    `streak_last_solved_date` DATETIME,
    `streak_current_streak` INT NOT NULL DEFAULT 0,
    `streak_longest_streak` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_user_name` (`name`),
    KEY `idx_user_ranking` (`solved_count` DESC, `streak_last_solved_date` ASC, `id` ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `problem` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255),
    `contest_id` BIGINT,
    `contest_num` BIGINT,
    PRIMARY KEY (`id`),
    KEY `idx_problem_contest` (`contest_id`),
    CONSTRAINT `fk_problem_contest`
        FOREIGN KEY (`contest_id`) REFERENCES `contest` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `submission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT,
    `submitted_time` DATETIME,
    `problem_id` BIGINT,
    `code` LONGTEXT NOT NULL,
    `code_hash` VARCHAR(64) NOT NULL,
    `result` VARCHAR(255),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_submission_user_problem_code_hash` (`user_id`, `problem_id`, `code_hash`),
    KEY `idx_submission_user_id_id` (`user_id`, `id`),
    KEY `idx_submission_problem_id_id` (`problem_id`, `id`),
    CONSTRAINT `fk_submission_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_submission_problem`
        FOREIGN KEY (`problem_id`) REFERENCES `problem` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `accepted_submission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT,
    `problem_id` BIGINT,
    `submitted_time` DATETIME,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_acc_sub_user_problem` (`user_id`, `problem_id`),
    CONSTRAINT `fk_acc_sub_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_acc_sub_problem`
        FOREIGN KEY (`problem_id`) REFERENCES `problem` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `user_problem_guard` (
    `user_id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`, `problem_id`),
    KEY `idx_upg_problem` (`problem_id`),
    CONSTRAINT `fk_upg_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_upg_problem`
        FOREIGN KEY (`problem_id`) REFERENCES `problem` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `contest_submission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `contest_id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `submitted_time` DATETIME NOT NULL,
    `code` LONGTEXT NOT NULL,
    `code_hash` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cs_code_hash` (`contest_id`, `problem_id`, `user_id`, `code_hash`),
    KEY `idx_contest_submission_contest` (`contest_id`),
    KEY `idx_contest_submission_problem` (`problem_id`),
    KEY `idx_contest_submission_user` (`user_id`),
    CONSTRAINT `fk_cs_contest`
        FOREIGN KEY (`contest_id`) REFERENCES `contest` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_cs_problem`
        FOREIGN KEY (`problem_id`) REFERENCES `problem` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_cs_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `contest_submission_result` (
    `submission_id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `provisional_result` VARCHAR(255) NOT NULL,
    `provisional_judged_at` DATETIME,
    `final_result` VARCHAR(255),
    `final_judged_at` DATETIME,
    PRIMARY KEY (`submission_id`),
    KEY `idx_csr_contest_submission` (`contest_id`, `submission_id`),
    KEY `idx_csr_contest_result_submission` (`contest_id`, `provisional_result`, `submission_id`),
    CONSTRAINT `fk_cs_result_submission`
        FOREIGN KEY (`submission_id`) REFERENCES `contest_submission` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_csr_contest`
        FOREIGN KEY (`contest_id`) REFERENCES `contest` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `contest_submission_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `contest_submission_id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `contest_start` DATETIME,
    `submitted_time` DATETIME NOT NULL,
    `judged_at` DATETIME,
    `result` VARCHAR(255) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `processed_at` DATETIME,
    `last_error_message` VARCHAR(500),
    PRIMARY KEY (`id`),
    KEY `idx_cs_outbox_submission` (`contest_submission_id`),
    KEY `idx_cs_outbox_contest` (`contest_id`),
    KEY `idx_cs_outbox_status_created` (`status`, `created_at`),
    CONSTRAINT `fk_cs_outbox_submission`
        FOREIGN KEY (`contest_submission_id`) REFERENCES `contest_submission` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `contest_final_score` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `contest_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `solved_count` INT NOT NULL,
    `penalty` BIGINT NOT NULL,
    `rank` INT NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cfs_contest_user_status` (`contest_id`, `user_id`, `status`),
    KEY `idx_cfs_contest_status_rank` (`contest_id`, `status`, `rank`),
    CONSTRAINT `fk_cfs_contest`
        FOREIGN KEY (`contest_id`) REFERENCES `contest` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_cfs_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `daily_active_users` (
    `day` DATE NOT NULL,
    `user_id` BIGINT NOT NULL,
    `last_active_time` DATETIME NOT NULL,
    PRIMARY KEY (`day`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `solved_count_bucket` (
    `n` BIGINT NOT NULL,
    `user_count` BIGINT NOT NULL,
    `cum_higher_count` BIGINT NOT NULL,
    PRIMARY KEY (`n`),
    KEY `idx_solved_bucket_cum_higher` (`cum_higher_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `streak_snapshot_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `snapshot_date` DATE NOT NULL,
    `user_id` BIGINT NOT NULL,
    `current_streak` INT NOT NULL,
    `last_solved_date` DATETIME NOT NULL,
    `rank` INT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ssu_date_user` (`snapshot_date`, `user_id`),
    KEY `idx_ssu_snapshot_date_rank` (`snapshot_date`, `rank`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
