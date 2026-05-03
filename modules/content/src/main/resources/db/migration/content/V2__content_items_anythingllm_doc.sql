-- Track the AnythingLLM document location returned when an item is pushed
-- into the tenant's workspace. Mirrors content_files.anythingllm_doc and
-- lets ContentItemService cleanly replace on update / remove on delete.
ALTER TABLE content_items
    ADD COLUMN anythingllm_doc VARCHAR(512) NULL AFTER content_data;
