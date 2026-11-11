--liquibase formatted sql
--changeset u8:semester
CREATE TABLE semester (
    id bigserial PRIMARY KEY,
    title text NOT NULL,
    starts_at timestamptz NOT NULL
);

CREATE TABLE semester_accrual (
    semester_id bigint NOT NULL REFERENCES semester(id),
    student_id bigint NOT NULL REFERENCES app_user(id),
    accrued integer NOT NULL CHECK (accrued >= 0),
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (semester_id, student_id)
);

INSERT INTO semester (title, starts_at) VALUES
    ('Осень 2026', '2026-08-31 21:00:00+00'),
    ('Весна 2027', '2027-02-08 21:00:00+00');
