CREATE TABLE drawing_member_library (
    member_id VARCHAR(32) NOT NULL PRIMARY KEY,
    items_json LONGTEXT NOT NULL,
    CONSTRAINT fk_drawing_member_library_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
