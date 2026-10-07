SET SESSION cte_max_recursion_depth = 100000;
SET @password = '$2y$10$v6eZSBsguEma4GybJfvIoeY7Jnbp552UH5DHtERP8QOTELfKYBs9S';
SET @complaints_per_room = 500;

INSERT INTO Users (email, password, user_name, user_status, user_role, created_at, updated_at)
WITH RECURSIVE buildings(no) AS (SELECT 1 UNION ALL SELECT no + 1 FROM buildings WHERE no < 5)
SELECT CONCAT('loadtest.manager', LPAD(no, 2, '0'), '@zipsai.com'), @password, CONCAT('관리자', no),
       'ACTIVE', 'MANAGER', NOW(6), NOW(6)
FROM buildings;

INSERT INTO Buildings (user_id, building_name, road_address, created_at, updated_at)
SELECT user_id, CONCAT('b', SUBSTRING(email, 17, 2)), '서울특별시 강남구 테헤란로 1', NOW(6), NOW(6)
FROM Users
WHERE email LIKE 'loadtest.manager%';

INSERT INTO Users (email, password, user_name, user_status, user_role, created_at, updated_at)
WITH RECURSIVE floors(no) AS (SELECT 1 UNION ALL SELECT no + 1 FROM floors WHERE no < 5),
               units(no) AS (SELECT 1 UNION ALL SELECT no + 1 FROM units WHERE no < 4)
SELECT CONCAT('loadtest.', b.building_name, '.r', floors.no, '0', units.no, '@zipsai.com'), @password,
       CONCAT(floors.no, '0', units.no, '호'), 'ACTIVE', 'RESIDENT', NOW(6), NOW(6)
FROM Buildings b
CROSS JOIN floors
CROSS JOIN units;

INSERT INTO Rooms (building_id, user_id, room_status, room_no, created_at, updated_at)
SELECT b.building_id, u.user_id, 'LIVING', SUBSTRING_INDEX(SUBSTRING_INDEX(u.email, '.r', -1), '@', 1),
       NOW(6), NOW(6)
FROM Users u
JOIN Buildings b ON u.email LIKE CONCAT('loadtest.', b.building_name, '.r%')
WHERE u.user_role = 'RESIDENT';

INSERT INTO Conversations (user_id, conversation_type, conversation_status, conversation_title, current_route,
                           last_message_at, created_at, updated_at)
WITH RECURSIVE seq(no) AS (SELECT 1 UNION ALL SELECT no + 1 FROM seq WHERE no < @complaints_per_room)
SELECT r.user_id, 'COMPLAINT', 'COMPLAINT_CREATED', CONCAT('loadtest-', r.room_id, '-', seq.no), 'COMPLAINT',
       times.created_at, times.created_at, times.created_at
FROM Rooms r
CROSS JOIN seq
CROSS JOIN LATERAL (
    SELECT NOW(6) - INTERVAL ((seq.no - 1) * 100 + r.room_id) MINUTE AS created_at
) times;

INSERT INTO Complaints (conversation_id, user_id, building_id, title, complaint_status, urgency, room_no,
                        resolved_at, created_at, updated_at)
SELECT c.conversation_id, c.user_id, r.building_id, CONCAT(r.room_no, '호 천장 누수'),
       ELT(c.conversation_id % 3 + 1, 'PENDING', 'IN_PROGRESS', 'DONE'),
       c.conversation_id % 11,
       r.room_no,
       IF(c.conversation_id % 3 = 2, c.created_at + INTERVAL 1 DAY, NULL),
       c.created_at, c.created_at
FROM Conversations c
JOIN Rooms r ON r.user_id = c.user_id
WHERE c.conversation_title LIKE 'loadtest-%';

INSERT INTO Complaint_Details (complaint_id, location, symptom, occurred_time, ai_summary, created_at, updated_at)
SELECT complaint_id, '안방 천장', '천장에서 물이 떨어집니다', created_at,
       '안방 천장에서 물이 떨어지는 누수 민원입니다', created_at, created_at
FROM Complaints;

SELECT
    (SELECT COUNT(*) FROM Users WHERE email LIKE 'loadtest.%') AS users,
    (SELECT COUNT(*) FROM Rooms WHERE room_status = 'LIVING') AS living_rooms,
    (SELECT COUNT(*) FROM Complaints) AS complaints;
