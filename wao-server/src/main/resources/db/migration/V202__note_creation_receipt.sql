-- 创建请求与正文同事务保存；AI/用户后续编辑不改变它，重试不能退回旧正文。
CREATE TABLE note_creation_receipt (
    doc_id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    FOREIGN KEY (doc_id) REFERENCES doc(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
