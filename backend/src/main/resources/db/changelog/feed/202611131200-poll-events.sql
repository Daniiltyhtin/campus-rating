--liquibase formatted sql
--changeset u16:poll-events
ALTER TABLE user_event DROP CONSTRAINT user_event_kind_check;
ALTER TABLE user_event ADD CONSTRAINT user_event_kind_check CHECK (kind IN ('ASSIGNMENT_CREATED', 'DEADLINE_REMINDER',
    'WORK_ACCEPTED', 'WORK_RETURNED', 'POINTS_AWARDED', 'ACTIVITY_CREATED', 'WORK_SUBMITTED', 'POLL_CREATED', 'POLL_CLOSED'));
ALTER TABLE user_event ADD COLUMN voted integer CHECK (voted >= 0);
ALTER TABLE user_event ADD COLUMN eligible integer CHECK (eligible >= 0);
