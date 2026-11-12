--liquibase formatted sql
--changeset campus:user-times
ALTER TABLE app_user ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE app_user ADD COLUMN last_login_at timestamptz;
CREATE INDEX app_user_created_idx ON app_user (created_at DESC, full_name, id);
CREATE INDEX app_user_last_login_idx ON app_user (last_login_at NULLS FIRST, full_name, id);
