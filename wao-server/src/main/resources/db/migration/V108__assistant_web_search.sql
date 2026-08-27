-- V108：普通“问问 AI”按需联网；本地 Codex 直接搜索，API Provider 先保存隔离 Runner 的有界搜索材料。
ALTER TABLE ai_assistant_run
    MODIFY COLUMN status VARCHAR(32) NOT NULL,
    ADD COLUMN web_search_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '本次页面问答是否允许隔离 Web Search' AFTER ai_capability_id,
    ADD COLUMN web_context MEDIUMTEXT NULL
        COMMENT 'Provider 最终回答前的有界联网材料，终态清除' AFTER prompt_text;
