-- DRY_RUN 결과를 실제 발행과 구분해 보존한다.
ALTER TABLE task_items DROP CONSTRAINT task_items_ticket_status_check;
ALTER TABLE task_items ADD CONSTRAINT task_items_ticket_status_check CHECK (ticket_status IN (
    'ASSIGNEE_REQUIRED', 'PENDING_REVIEW', 'CONFIRMED', 'PUBLISHING', 'REJECTED',
    'PUBLISHED', 'LINKED', 'UPDATED', 'SKIPPED', 'FAILED'
));
