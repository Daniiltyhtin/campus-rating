--liquibase formatted sql
--changeset u13:temporary-passwords
CREATE TABLE temporary_password_audit (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    actor_name text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX temporary_password_audit_user ON temporary_password_audit(user_id,created_at DESC,id DESC);
