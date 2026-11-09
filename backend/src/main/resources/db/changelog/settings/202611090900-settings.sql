--liquibase formatted sql
--changeset u4:settings
CREATE TABLE settings (
    key text PRIMARY KEY,
    value text NOT NULL,
    type text NOT NULL CHECK (type IN ('INTEGER', 'DURATION'))
);
