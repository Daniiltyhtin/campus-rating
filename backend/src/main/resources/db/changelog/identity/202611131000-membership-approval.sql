--liquibase formatted sql
--changeset u15:membership-approval
ALTER TABLE group_membership DROP CONSTRAINT group_membership_state_check;
ALTER TABLE group_membership DROP CONSTRAINT group_membership_check;
ALTER TABLE group_membership ADD CONSTRAINT group_membership_state_check
    CHECK (state IN ('PENDING', 'ACTIVE', 'REJECTED'));
ALTER TABLE group_membership ADD CONSTRAINT group_membership_check
    CHECK ((state = 'ACTIVE' AND activated_at IS NOT NULL) OR (state <> 'ACTIVE' AND activated_at IS NULL));
ALTER TABLE group_membership ADD COLUMN requested_at timestamptz;
UPDATE group_membership SET requested_at = coalesce(activated_at, now());
ALTER TABLE group_membership ALTER COLUMN requested_at SET DEFAULT now();
ALTER TABLE group_membership ALTER COLUMN requested_at SET NOT NULL;
CREATE INDEX group_membership_pending_idx ON group_membership (group_id, requested_at, student_id) WHERE state = 'PENDING';
CREATE TABLE group_head (
    group_id bigint PRIMARY KEY REFERENCES study_group(id),
    student_id bigint NOT NULL UNIQUE REFERENCES app_user(id),
    assigned_at timestamptz NOT NULL
);
