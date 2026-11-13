--liquibase formatted sql
--changeset u22:demo-email-verified-all context:demo
UPDATE app_user SET email_verified = true
WHERE id IN (1001, 1002, 1003, 1004, 11001, 11002, 11003, 11004, 11005, 11006, 2001, 2002, 3001);
