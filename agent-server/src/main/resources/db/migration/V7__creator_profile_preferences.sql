ALTER TABLE agent_workspace_profile
    ADD COLUMN content_topics VARCHAR(500) NULL AFTER avoid,
    ADD COLUMN length_structure VARCHAR(500) NULL AFTER content_topics,
    ADD COLUMN update_frequency VARCHAR(200) NULL AFTER length_structure,
    ADD COLUMN primary_platforms VARCHAR(200) NULL AFTER update_frequency,
    ADD COLUMN image_style VARCHAR(500) NULL AFTER primary_platforms;
