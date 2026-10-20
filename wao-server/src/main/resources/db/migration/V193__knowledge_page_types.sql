ALTER TABLE doc ADD COLUMN page_type VARCHAR(20) NOT NULL DEFAULT 'document', ADD COLUMN drawing_id VARCHAR(32) NULL, ADD UNIQUE KEY uk_doc_drawing (drawing_id);
ALTER TABLE drawing_file ADD COLUMN knowledge_doc_id VARCHAR(32) NULL, ADD UNIQUE KEY uk_drawing_doc (knowledge_doc_id), ADD CONSTRAINT fk_drawing_knowledge_doc FOREIGN KEY (knowledge_doc_id) REFERENCES doc(id) ON DELETE CASCADE;
