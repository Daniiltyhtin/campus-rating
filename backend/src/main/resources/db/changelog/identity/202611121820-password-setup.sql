--liquibase formatted sql
--changeset u13:password-setup
ALTER TABLE app_user ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
CREATE TABLE password_setup_token (
    token_hash text PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES app_user(id),
    purpose text NOT NULL CHECK(purpose IN ('PASSWORD_SETUP','EMAIL_VERIFICATION')),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX password_setup_token_user ON password_setup_token(user_id);
