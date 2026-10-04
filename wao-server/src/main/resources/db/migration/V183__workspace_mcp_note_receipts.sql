-- 只记录创建请求摘要，不保存正文、令牌或私人原稿。
CREATE TABLE workspace_mcp_note_receipt (
    member_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
