CREATE TABLE IF NOT EXISTS `user_guard` (
    `user_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_user_guard_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
