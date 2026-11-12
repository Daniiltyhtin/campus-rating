--liquibase formatted sql
--changeset u13:demo-email-verified context:demo
UPDATE app_user SET email_verified = true
WHERE email IN ('admin@campus.test', 'petrov@campus.test', 'smirnova@student.campus.test');
