-- HTML 页面托管：正文与可选站点图标持久化到主节点 MySQL，公开地址按唯一后缀寻址。
CREATE TABLE hosted_page (
    id VARCHAR(64) PRIMARY KEY,
    slug VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    html_content MEDIUMBLOB NOT NULL,
    icon_content MEDIUMBLOB NULL,
    icon_content_type VARCHAR(100) NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_hosted_page_slug (slug)
);
