--liquibase formatted sql
--changeset u16:demo-polls context:demo
INSERT INTO poll(id, author_id, title, description, ends_at, anonymous, created_at) VALUES
    (31001, 2001, 'Тема курсового проекта', 'Выберите направления, которые вам интересны.', now() - interval '1 hour', false,
        now() - interval '3 days'),
    (31002, 2001, 'Формат зачёта', 'Выберите удобное время сдачи.', now() + interval '3 days', true, now() - interval '1 day');
INSERT INTO poll_group(poll_id, group_id) VALUES (31001, 11), (31002, 11);
INSERT INTO poll_question(id, poll_id, position, text, type) VALUES
    (31101, 31001, 0, 'Какие темы интересны?', 'MULTIPLE'),
    (31102, 31002, 0, 'Когда удобнее?', 'SINGLE');
INSERT INTO poll_option(id, question_id, position, text, vote_count) VALUES
    (31201, 31101, 0, 'Веб-сервисы', 3),
    (31202, 31101, 1, 'Мобильные приложения', 2),
    (31203, 31101, 2, 'Анализ данных', 2),
    (31204, 31102, 0, 'Утро', 1),
    (31205, 31102, 1, 'Вечер', 0);
INSERT INTO poll_participation(poll_id, student_id, voted_at) VALUES
    (31001, 1002, now() - interval '2 days'), (31001, 1003, now() - interval '2 days'),
    (31001, 11001, now() - interval '30 hours'), (31001, 11002, now() - interval '20 hours'),
    (31002, 1002, now() - interval '5 hours');
INSERT INTO poll_named_choice(poll_id, option_id, student_id) VALUES
    (31001, 31201, 1002), (31001, 31203, 1002),
    (31001, 31202, 1003),
    (31001, 31201, 11001), (31001, 31202, 11001),
    (31001, 31201, 11002), (31001, 31203, 11002);
