CREATE TABLE note_polish_version (
    doc_id VARCHAR(32) NOT NULL COMMENT '小记文档',
    member_id VARCHAR(32) NOT NULL COMMENT '发起润色的成员',
    source_markdown LONGTEXT NOT NULL COMMENT '润色时已保存的完整正文快照',
    polished_markdown LONGTEXT NOT NULL COMMENT 'AI 返回的润色版正文',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (doc_id, member_id),
    KEY idx_note_polish_member (member_id, updated_at),
    CONSTRAINT fk_note_polish_doc FOREIGN KEY (doc_id) REFERENCES doc(id) ON DELETE CASCADE,
    CONSTRAINT fk_note_polish_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小记正文原文与润色版对照';
