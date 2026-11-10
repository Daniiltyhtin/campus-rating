--liquibase formatted sql
--changeset u5:demo-users context:demo
INSERT INTO app_user(id,email,role,full_name,password_hash,department_id) VALUES
    (1001,'smirnova@student.campus.test','STUDENT','Смирнова Анна Олеговна','$2b$10$4vWzYsUhlRRKtqC.YW2dBuBhaF/HHHtgL37gi9w8eZGp98rOzPeQu',NULL),
    (1002,'kuznetsov@student.campus.test','STUDENT','Кузнецов Дмитрий Андреевич','$2b$10$4vWzYsUhlRRKtqC.YW2dBuBhaF/HHHtgL37gi9w8eZGp98rOzPeQu',NULL),
    (1003,'novikov@student.campus.test','STUDENT','Новиков Артём Павлович','$2b$10$4vWzYsUhlRRKtqC.YW2dBuBhaF/HHHtgL37gi9w8eZGp98rOzPeQu',NULL),
    (1004,'belova@student.campus.test','STUDENT','Белова Ольга Сергеевна','$2b$10$4vWzYsUhlRRKtqC.YW2dBuBhaF/HHHtgL37gi9w8eZGp98rOzPeQu',NULL),
    (2001,'petrov@campus.test','TEACHER','Петров Павел Петрович','$2b$10$OUpAapo0zogFtI0Cyp2y1.LZ7DLRd97SUAqkUGJck0kNsb.1J8Zdy',101),
    (2002,'teacher-pending@campus.test','TEACHER','Преподаватель без допуска','$2b$10$OUpAapo0zogFtI0Cyp2y1.LZ7DLRd97SUAqkUGJck0kNsb.1J8Zdy',101),
    (3001,'admin@campus.test','ADMIN','Администратор Демо','$2b$10$uffqYJxkuAVHhABF6OEotOEYlLbw97nRzC3.YhS/vJC/S5hzEg.bu',NULL)
ON CONFLICT DO NOTHING;
INSERT INTO teacher_allowlist(email,department_id) VALUES ('petrov@campus.test',101),('teacher-pending@campus.test',101)
ON CONFLICT DO NOTHING;
INSERT INTO teacher_approval(teacher_id) VALUES (2001) ON CONFLICT DO NOTHING;
SELECT setval(pg_get_serial_sequence('app_user','id'), GREATEST((SELECT max(id) FROM app_user), (SELECT last_value FROM app_user_id_seq)));
