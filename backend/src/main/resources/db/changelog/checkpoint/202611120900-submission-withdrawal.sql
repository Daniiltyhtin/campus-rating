--liquibase formatted sql
--changeset u7:submission-withdrawal
ALTER TABLE submission ADD COLUMN withdrawn_at timestamptz;
