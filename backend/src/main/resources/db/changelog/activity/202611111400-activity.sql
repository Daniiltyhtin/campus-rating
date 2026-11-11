--liquibase formatted sql
--changeset u9:activity
CREATE TABLE activity (
    id bigserial PRIMARY KEY,
    author_id bigint NOT NULL REFERENCES app_user(id),
    title text NOT NULL,
    description text NOT NULL,
    type text NOT NULL CHECK (type IN ('ACADEMIC', 'SOCIAL')),
    starts_at timestamptz NOT NULL,
    max_points integer NOT NULL CHECK (max_points BETWEEN 2 AND 10000),
    created_at timestamptz NOT NULL
);
CREATE TABLE activity_group (
    activity_id bigint NOT NULL REFERENCES activity(id),
    group_id bigint NOT NULL REFERENCES study_group(id),
    PRIMARY KEY (activity_id, group_id)
);
CREATE INDEX activity_group_group_idx ON activity_group (group_id, activity_id);
CREATE TABLE activity_enrollment (
    activity_id bigint NOT NULL REFERENCES activity(id),
    student_id bigint NOT NULL REFERENCES app_user(id),
    enrolled_at timestamptz NOT NULL,
    PRIMARY KEY (activity_id, student_id)
);
CREATE TABLE activity_award (
    id bigserial PRIMARY KEY,
    activity_id bigint NOT NULL REFERENCES activity(id),
    student_id bigint NOT NULL REFERENCES app_user(id),
    group_id bigint NOT NULL REFERENCES study_group(id),
    level integer NOT NULL CHECK (level IN (25, 50, 75, 100)),
    amount integer NOT NULL CHECK (amount > 0),
    currency text NOT NULL CHECK (currency IN ('BUA', 'BOA')),
    author_id bigint NOT NULL REFERENCES app_user(id),
    confirmed_at timestamptz NOT NULL,
    ledger_entry_id bigint REFERENCES ledger_entry(id),
    UNIQUE (activity_id, student_id)
);
CREATE INDEX activity_award_author_history_idx ON activity_award (author_id, confirmed_at DESC, id DESC);
CREATE TABLE activity_budget (
    id bigserial PRIMARY KEY,
    teacher_id bigint NOT NULL REFERENCES app_user(id),
    group_id bigint NOT NULL REFERENCES study_group(id),
    month date NOT NULL,
    spent integer NOT NULL CHECK (spent >= 0),
    UNIQUE (teacher_id, group_id, month)
);
