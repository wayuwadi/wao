CREATE TABLE http_request_metric (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    member_id      VARCHAR(32)  NOT NULL,
    method         VARCHAR(8)   NOT NULL,
    request_path   VARCHAR(255) NOT NULL,
    status_code    SMALLINT     NULL,
    duration_ms    INT          NOT NULL,
    network_type   VARCHAR(16)  NULL,
    retry_attempt  TINYINT      NOT NULL DEFAULT 0,
    started_at     DATETIME(3)  NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_http_metric_member_started (member_id, started_at),
    KEY idx_http_metric_started (started_at),
    CONSTRAINT fk_http_metric_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
