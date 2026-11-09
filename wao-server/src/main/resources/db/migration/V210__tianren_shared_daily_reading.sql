CREATE TABLE tianren_shared_daily_reading (
    reading_date DATE NOT NULL PRIMARY KEY,
    lines_json VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
