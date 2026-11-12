--liquibase formatted sql
--changeset u20:demo-story context:demo
INSERT INTO app_user(id, email, role, full_name, password_hash)
SELECT v.id, v.email, 'STUDENT', v.full_name, u.password_hash
FROM (VALUES
    (11001, 'ivanova@student.campus.test', 'Иванова Мария Алексеевна'),
    (11002, 'sokolov@student.campus.test', 'Соколов Илья Дмитриевич'),
    (11003, 'lebedeva@student.campus.test', 'Лебедева Елена Викторовна'),
    (11004, 'morozov@student.campus.test', 'Морозов Михаил Денисович'),
    (11005, 'vasileva@student.campus.test', 'Васильева Софья Романовна'),
    (11006, 'fedorov@student.campus.test', 'Фёдоров Максим Игоревич')
) v(id, email, full_name) CROSS JOIN app_user u WHERE u.id = 1001
ON CONFLICT DO NOTHING;

-- Existing demo accounts are initialized before penalties; DemoIdentity uses the same idempotency keys.
INSERT INTO group_membership(student_id, group_id, state, activated_at)
SELECT id, CASE WHEN id < 11004 THEN 11 ELSE 12 END, 'ACTIVE', now() - interval '3 days'
FROM app_user WHERE id IN (1001, 1002, 1003, 11001, 11002, 11003, 11004, 11005, 11006)
ON CONFLICT DO NOTHING;
INSERT INTO ledger_entry(id, student_id, currency, kind, amount, source_type, source_title, idempotency_key, created_at)
SELECT 26000 + row_number() OVER (ORDER BY id), id, 'AR', 'INITIAL', 1000,
    'REGISTRATION', 'Регистрация', 'initial:' || id, now() - interval '3 days'
FROM app_user WHERE id IN (1001, 1002, 1003, 1004, 11001, 11002, 11003, 11004, 11005, 11006)
ON CONFLICT DO NOTHING;

INSERT INTO checkpoint(id, author_id, title, description, deadline, cost, penalty_step,
    penalty_interval_seconds, created_at, updated_at) VALUES
    (21001, 2001, 'Лабораторная работа: анализ данных',
        'Сдайте ссылку на отчёт с результатами и выводами.', now() - interval '20 minutes',
        60, 4, 60, now() - interval '2 days', now() - interval '2 days'),
    (21002, 2001, 'Проект: модель университетского сервиса',
        'Приложите ссылку на модель и описание решений.', now() + interval '2 days',
        60, 4, 60, now() - interval '1 day', now() - interval '1 day')
ON CONFLICT DO NOTHING;
INSERT INTO checkpoint_group(checkpoint_id, group_id) VALUES
    (21001, 11), (21001, 12), (21002, 11), (21002, 12)
ON CONFLICT DO NOTHING;
INSERT INTO assignment(id, checkpoint_id, student_id, group_id, student_full_name, state,
    charged, last_penalty_slot, accepted_at, created_at)
SELECT v.id, 21001, u.id, m.group_id, u.full_name, v.state, v.charged, v.slot,
    CASE WHEN v.state = 'ACCEPTED' THEN now() - interval '25 minutes' END, now() - interval '2 days'
FROM (VALUES
    (22001, 1001, 'OPEN', 60, 14),
    (22002, 1002, 'SUBMITTED', 0, NULL),
    (22003, 1003, 'ACCEPTED', 0, NULL),
    (22004, 11001, 'SUBMITTED', 0, NULL),
    (22005, 11002, 'OPEN', 12, 19),
    (22006, 11003, 'OPEN', 4, 19),
    (22007, 11004, 'OPEN', 8, 19),
    (22008, 11005, 'SUBMITTED', 0, NULL),
    (22009, 11006, 'OPEN', 0, NULL)
) v(id, student_id, state, charged, slot)
JOIN app_user u ON u.id = v.student_id JOIN group_membership m ON m.student_id = u.id
ON CONFLICT DO NOTHING;
INSERT INTO assignment(id, checkpoint_id, student_id, group_id, student_full_name, state, created_at)
SELECT id + 10, 21002, student_id, group_id, student_full_name, 'OPEN', now() - interval '1 day'
FROM assignment WHERE checkpoint_id = 21001
ON CONFLICT DO NOTHING;
INSERT INTO submission(id, assignment_id, kind, url, submitted_at, return_comment, returned_at) VALUES
    (23001, 22002, 'LINK', 'https://example.org/reports/kuznetsov', now() - interval '30 minutes', NULL, NULL),
    (23002, 22003, 'LINK', 'https://example.org/reports/novikov', now() - interval '40 minutes', NULL, NULL),
    (23003, 22004, 'LINK', 'https://example.org/reports/ivanova', now() - interval '35 minutes', NULL, NULL),
    (23004, 22006, 'LINK', 'https://example.org/reports/lebedeva', now() - interval '30 minutes',
        'Добавьте выводы', now() - interval '2 minutes'),
    (23005, 22008, 'LINK', 'https://example.org/reports/vasileva', now() - interval '25 minutes', NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO ledger_entry(id, student_id, currency, kind, amount, source_type, source_id,
    source_title, idempotency_key, created_at)
SELECT 26100 + row_number() OVER (ORDER BY a.id, slots.slot), a.student_id, 'AR', 'PENALTY', -4,
    'CHECKPOINT', c.id, c.title, 'penalty:' || a.id || ':' || slots.slot,
    c.deadline + slots.slot * interval '60 seconds' + interval '1 second'
FROM (VALUES (22001, 0, 14), (22005, 17, 19), (22006, 19, 19), (22007, 18, 19)) v(id, first_slot, last_slot)
JOIN assignment a ON a.id = v.id JOIN checkpoint c ON c.id = a.checkpoint_id
CROSS JOIN LATERAL generate_series(v.first_slot, v.last_slot) slots(slot)
ON CONFLICT DO NOTHING;

INSERT INTO activity(id, author_id, title, description, type, starts_at, max_points, created_at) VALUES
    (24001, 2001, 'Волонтёрский день кампуса', 'Помощь в организации дня открытых дверей.',
        'SOCIAL', now() - interval '2 hours', 100, now() - interval '2 days'),
    (24002, 2001, 'Учебная конференция', 'Доклады и обсуждение исследовательских проектов.',
        'ACADEMIC', now() - interval '1 hour', 800, now() - interval '2 days'),
    (24003, 2001, 'Клуб настольных игр', 'Знакомство студентов двух групп; запись открыта.',
        'SOCIAL', now() + interval '1 day', 40, now() - interval '1 day')
ON CONFLICT DO NOTHING;
INSERT INTO activity_group(activity_id, group_id)
SELECT a.id, g.id FROM activity a CROSS JOIN study_group g
WHERE a.id BETWEEN 24001 AND 24003 AND g.id IN (11, 12)
ON CONFLICT DO NOTHING;
INSERT INTO activity_enrollment(activity_id, student_id, enrolled_at) VALUES
    (24001, 1001, now() - interval '1 day'), (24001, 1002, now() - interval '1 day'),
    (24001, 11002, now() - interval '1 day'), (24001, 11004, now() - interval '1 day'),
    (24003, 1001, now()), (24003, 11005, now())
ON CONFLICT DO NOTHING;
INSERT INTO ledger_entry(id, student_id, currency, kind, amount, source_type, source_id,
    source_title, author_id, author_name, idempotency_key, created_at)
SELECT v.id, v.student_id, v.currency, 'ACTIVITY_AWARD', v.amount, 'ACTIVITY', a.id,
    a.title, a.author_id, u.full_name, 'award:' || a.id || ':' || v.student_id, now()
FROM (VALUES
    (26201, 24001, 1001, 'BOA', 25), (26202, 24001, 1002, 'BOA', 50),
    (26203, 24001, 11002, 'BOA', 75), (26204, 24001, 11004, 'BOA', 100),
    (26205, 24002, 11001, 'BUA', 800), (26206, 24002, 11005, 'BUA', 400)
) v(id, activity_id, student_id, currency, amount)
JOIN activity a ON a.id = v.activity_id JOIN app_user u ON u.id = a.author_id
ON CONFLICT DO NOTHING;
INSERT INTO activity_award(id, activity_id, student_id, group_id, level, amount, currency,
    author_id, confirmed_at, ledger_entry_id)
SELECT e.id + 800, a.id, e.student_id, m.group_id, e.amount * 100 / a.max_points, e.amount,
    e.currency, e.author_id, e.created_at, e.id
FROM ledger_entry e JOIN activity a ON a.id = e.source_id
JOIN group_membership m ON m.student_id = e.student_id
WHERE e.id BETWEEN 26201 AND 26206
ON CONFLICT DO NOTHING;
INSERT INTO activity_budget(id, teacher_id, group_id, month, spent)
SELECT 28000 + row_number() OVER (ORDER BY author_id, group_id,
    date_trunc('month', confirmed_at AT TIME ZONE 'Europe/Moscow')),
    author_id, group_id, date_trunc('month', confirmed_at AT TIME ZONE 'Europe/Moscow')::date, sum(amount)
FROM activity_award WHERE activity_id BETWEEN 24001 AND 24003
GROUP BY author_id, group_id, date_trunc('month', confirmed_at AT TIME ZONE 'Europe/Moscow')
ON CONFLICT DO NOTHING;
INSERT INTO ledger_balance(student_id, currency, amount)
SELECT student_id, currency, sum(amount) FROM ledger_entry
WHERE student_id IN (1001, 1002, 1003, 1004, 11001, 11002, 11003, 11004, 11005, 11006)
GROUP BY student_id, currency
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('app_user', 'id'), GREATEST((SELECT max(id) FROM app_user), (SELECT last_value FROM app_user_id_seq)));
SELECT setval(pg_get_serial_sequence('checkpoint', 'id'), GREATEST((SELECT max(id) FROM checkpoint), (SELECT last_value FROM checkpoint_id_seq)));
SELECT setval(pg_get_serial_sequence('assignment', 'id'), GREATEST((SELECT max(id) FROM assignment), (SELECT last_value FROM assignment_id_seq)));
SELECT setval(pg_get_serial_sequence('submission', 'id'), GREATEST((SELECT max(id) FROM submission), (SELECT last_value FROM submission_id_seq)));
SELECT setval(pg_get_serial_sequence('activity', 'id'), GREATEST((SELECT max(id) FROM activity), (SELECT last_value FROM activity_id_seq)));
SELECT setval(pg_get_serial_sequence('ledger_entry', 'id'), GREATEST((SELECT max(id) FROM ledger_entry), (SELECT last_value FROM ledger_entry_id_seq)));
SELECT setval(pg_get_serial_sequence('activity_award', 'id'), GREATEST((SELECT max(id) FROM activity_award), (SELECT last_value FROM activity_award_id_seq)));
SELECT setval(pg_get_serial_sequence('activity_budget', 'id'), GREATEST((SELECT max(id) FROM activity_budget), (SELECT last_value FROM activity_budget_id_seq)));
