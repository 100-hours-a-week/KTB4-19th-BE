-- 건물 등록 화면에서 건물명을 생략할 수 있도록 기존 NOT NULL 제약을 해제합니다.
ALTER TABLE Buildings
    MODIFY COLUMN building_name VARCHAR(20) NULL;
