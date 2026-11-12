--liquibase formatted sql
--changeset u13:admin-semesters
ALTER TABLE semester ADD COLUMN accrual integer CHECK (accrual BETWEEN 0 AND 1000);
CREATE UNIQUE INDEX semester_title_unique ON semester (lower(trim(title)));
UPDATE semester SET accrual = COALESCE((SELECT value::integer FROM settings WHERE key='semesterAccrual'),200)
WHERE EXISTS (SELECT 1 FROM semester_accrual a WHERE a.semester_id=semester.id);
