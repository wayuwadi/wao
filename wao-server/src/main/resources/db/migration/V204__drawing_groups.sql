CREATE TABLE drawing_group (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY idx_drawing_group_member (member_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE drawing_group_assignment (
    member_id VARCHAR(32) NOT NULL,
    file_id VARCHAR(32) NOT NULL,
    group_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (member_id, file_id),
    CONSTRAINT fk_drawing_assignment_file FOREIGN KEY (file_id) REFERENCES drawing_file(id) ON DELETE CASCADE,
    CONSTRAINT fk_drawing_assignment_group FOREIGN KEY (group_id) REFERENCES drawing_group(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
