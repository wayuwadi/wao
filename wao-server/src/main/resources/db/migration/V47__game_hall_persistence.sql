-- 游戏厅使用通用存档与对局记录表：新游戏只需新增 game_key 和业务规则，不再重复建表。
CREATE TABLE game_save (
    id                  VARCHAR(80)  NOT NULL,
    member_id           VARCHAR(32)  NOT NULL,
    game_key            VARCHAR(32)  NOT NULL,
    state_json          TEXT         NOT NULL,
    score               BIGINT       NOT NULL DEFAULT 0,
    move_count          INT          NOT NULL DEFAULT 0,
    won                 TINYINT(1)   NOT NULL DEFAULT 0,
    revision            INT          NOT NULL DEFAULT 0,
    previous_state_json TEXT         NULL,
    previous_score      BIGINT       NULL,
    previous_move_count INT          NULL,
    started_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_save_member_game (member_id, game_key),
    KEY idx_game_save_member (member_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE game_run_record (
    id            VARCHAR(32) NOT NULL,
    member_id     VARCHAR(32) NOT NULL,
    game_key      VARCHAR(32) NOT NULL,
    score         BIGINT      NOT NULL,
    highest_tile  INT         NOT NULL,
    move_count    INT         NOT NULL,
    outcome       VARCHAR(16) NOT NULL,
    started_at    DATETIME(3) NOT NULL,
    ended_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_game_run_member_game (member_id, game_key, ended_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
