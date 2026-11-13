--liquibase formatted sql
--changeset u24:user-name-parts
ALTER TABLE app_user ADD COLUMN last_name text, ADD COLUMN first_name text, ADD COLUMN middle_name text;

WITH names AS (
    SELECT id, string_to_array(btrim(regexp_replace(full_name, '[[:space:]]+', ' ', 'g')), ' ') AS parts
    FROM app_user
)
UPDATE app_user u SET last_name = n.parts[1], first_name = coalesce(n.parts[2], n.parts[1]),
    middle_name = nullif(array_to_string(n.parts[3:array_length(n.parts, 1)], ' '), '')
FROM names n WHERE n.id = u.id;

ALTER TABLE app_user
    ALTER COLUMN last_name SET NOT NULL,
    ALTER COLUMN first_name SET NOT NULL,
    ADD CONSTRAINT app_user_last_name_check CHECK (char_length(last_name) BETWEEN 1 AND 100 AND last_name = btrim(last_name)),
    ADD CONSTRAINT app_user_first_name_check CHECK (char_length(first_name) BETWEEN 1 AND 100 AND first_name = btrim(first_name)),
    ADD CONSTRAINT app_user_middle_name_check CHECK (middle_name IS NULL OR (char_length(middle_name) BETWEEN 1 AND 100 AND middle_name = btrim(middle_name)));

DROP INDEX app_user_created_idx;
DROP INDEX app_user_last_login_idx;
CREATE INDEX app_user_created_idx ON app_user (created_at DESC, last_name, first_name, middle_name, id);
CREATE INDEX app_user_last_login_idx ON app_user (last_login_at NULLS FIRST, last_name, first_name, middle_name, id);
ALTER TABLE app_user DROP COLUMN full_name;
