--liquibase formatted sql
--changeset u6:ledger
CREATE TABLE ledger_entry (
    id bigserial PRIMARY KEY,
    student_id bigint NOT NULL REFERENCES app_user(id),
    currency text NOT NULL CHECK (currency IN ('AR', 'BUA', 'BOA')),
    kind text NOT NULL CHECK (kind IN ('INITIAL', 'SEMESTER_ACCRUAL', 'PENALTY', 'ACTIVITY_AWARD')),
    amount integer NOT NULL CHECK (amount <> 0),
    source_type text NOT NULL CHECK (source_type IN ('REGISTRATION', 'SEMESTER', 'CHECKPOINT', 'ACTIVITY')),
    source_id bigint,
    source_title text NOT NULL,
    author_id bigint,
    author_name text,
    idempotency_key text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL
);
CREATE INDEX ledger_entry_student_history_idx ON ledger_entry (student_id, created_at DESC, id DESC);
CREATE INDEX ledger_entry_awards_idx ON ledger_entry (created_at DESC, id DESC) WHERE kind = 'ACTIVITY_AWARD';

CREATE TABLE ledger_balance (
    student_id bigint NOT NULL REFERENCES app_user(id),
    currency text NOT NULL CHECK (currency IN ('AR', 'BUA', 'BOA')),
    amount integer NOT NULL,
    PRIMARY KEY (student_id, currency),
    CHECK (amount >= 0 AND (currency <> 'AR' OR amount <= 1000))
);
