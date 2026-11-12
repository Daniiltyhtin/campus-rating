--liquibase formatted sql
--changeset u14:demo-feed context:demo
INSERT INTO checkpoint(id, author_id, title, description, deadline, cost, penalty_step,
    penalty_interval_seconds, created_at, updated_at) VALUES
    (21003, 2001, 'Эссе: роль студенческих сообществ', 'Сдайте ссылку на эссе объёмом до трёх страниц.',
        now() - interval '1 day', 60, 4, 60, now() - interval '3 days', now() - interval '3 days')
ON CONFLICT DO NOTHING;
INSERT INTO checkpoint_group(checkpoint_id, group_id) VALUES (21003, 11), (21003, 12)
ON CONFLICT DO NOTHING;
INSERT INTO assignment(id, checkpoint_id, student_id, group_id, student_full_name, state, accepted_at, created_at)
SELECT id + 20, 21003, student_id, group_id, student_full_name, 'ACCEPTED', now() - interval '2 days',
    now() - interval '3 days'
FROM assignment WHERE checkpoint_id = 21001
ON CONFLICT DO NOTHING;
INSERT INTO submission(id, assignment_id, kind, url, submitted_at, return_comment, returned_at) VALUES
    (23100, 22021, 'LINK', 'https://example.org/essays/smirnova-draft', now() - interval '2 days 6 hours',
        'Раскройте пример из практики', now() - interval '2 days 4 hours')
ON CONFLICT DO NOTHING;
INSERT INTO submission(id, assignment_id, kind, url, submitted_at)
SELECT 23100 + a.id - 22020, a.id, 'LINK', 'https://example.org/essays/' || a.student_id, now() - interval '2 days 2 hours'
FROM assignment a WHERE a.checkpoint_id = 21003
ON CONFLICT DO NOTHING;

INSERT INTO user_event(recipient_id, kind, dedup_key, created_at, target_id, title, deadline, amount, currency,
    student_full_name)
SELECT 1001, 'ASSIGNMENT_CREATED', 'demo:assignment:22011', now() - interval '1 day', 22011, c.title, c.deadline,
    NULL::integer, NULL::text, NULL::text
FROM checkpoint c WHERE c.id = 21002
UNION ALL
SELECT 1001, 'DEADLINE_REMINDER', 'demo:reminder:22001', c.deadline - interval '23 hours', 22001, c.title, c.deadline,
    NULL, NULL, NULL
FROM checkpoint c WHERE c.id = 21001
UNION ALL
SELECT 1001, 'WORK_RETURNED', 'demo:returned:23100', s.returned_at, 22021, c.title, NULL, NULL, NULL, NULL
FROM submission s JOIN checkpoint c ON c.id = 21003 WHERE s.id = 23100
UNION ALL
SELECT 1001, 'WORK_ACCEPTED', 'demo:accepted:22021', a.accepted_at, 22021, c.title, NULL, NULL, NULL, NULL
FROM assignment a JOIN checkpoint c ON c.id = a.checkpoint_id WHERE a.id = 22021
UNION ALL
SELECT e.student_id, 'POINTS_AWARDED', 'demo:award:' || e.id, e.created_at, e.source_id, e.source_title, NULL,
    e.amount, e.currency, NULL
FROM ledger_entry e WHERE e.id = 26201
UNION ALL
SELECT m.student_id, 'ACTIVITY_CREATED', 'demo:activity:24003', a.created_at, a.id, a.title, NULL, NULL, NULL, NULL
FROM activity a JOIN group_membership m ON m.student_id = 1001 WHERE a.id = 24003
UNION ALL
SELECT 2001, 'WORK_SUBMITTED', 'demo:submitted:23001', s.submitted_at, c.id, c.title, NULL, NULL, NULL,
    a.student_full_name
FROM submission s JOIN assignment a ON a.id = s.assignment_id JOIN checkpoint c ON c.id = a.checkpoint_id
WHERE s.id = 23001
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('checkpoint', 'id'), GREATEST((SELECT max(id) FROM checkpoint), (SELECT last_value FROM checkpoint_id_seq)));
SELECT setval(pg_get_serial_sequence('assignment', 'id'), GREATEST((SELECT max(id) FROM assignment), (SELECT last_value FROM assignment_id_seq)));
SELECT setval(pg_get_serial_sequence('submission', 'id'), GREATEST((SELECT max(id) FROM submission), (SELECT last_value FROM submission_id_seq)));
