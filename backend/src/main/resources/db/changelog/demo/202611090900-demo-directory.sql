--liquibase formatted sql
--changeset u4:demo-directory context:demo runOnChange:true
INSERT INTO faculty (id, code, name) VALUES
    (1, 'ФИТ', 'Факультет информационных технологий'),
    (2, 'ЭФ', 'Экономический факультет')
ON CONFLICT DO NOTHING;
INSERT INTO study_group (id, code, name, faculty_id, admission_year) VALUES
    (11, 'ИВТ-21', 'ИВТ-21', 1, 2025),
    (12, 'ИВТ-22', 'ИВТ-22', 1, 2026)
ON CONFLICT DO NOTHING;
INSERT INTO department (id, code, name, faculty_id) VALUES
    (101, 'ПИ', 'Кафедра программной инженерии', 1)
ON CONFLICT DO NOTHING;
SELECT setval(pg_get_serial_sequence('faculty', 'id'), GREATEST((SELECT max(id) FROM faculty), (SELECT last_value FROM faculty_id_seq)));
SELECT setval(pg_get_serial_sequence('study_group', 'id'), GREATEST((SELECT max(id) FROM study_group), (SELECT last_value FROM study_group_id_seq)));
SELECT setval(pg_get_serial_sequence('department', 'id'), GREATEST((SELECT max(id) FROM department), (SELECT last_value FROM department_id_seq)));
