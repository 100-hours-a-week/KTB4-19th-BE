INSERT INTO Terms (terms_type, version, title, content, effective_at, created_at, updated_at)
SELECT 'SERVICE', 1, '서비스 이용약관', '기본 서비스 이용약관입니다.', DATE '2000-01-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM Terms WHERE terms_type = 'SERVICE' AND version = 1
);

INSERT INTO Terms (terms_type, version, title, content, effective_at, created_at, updated_at)
SELECT 'PRIVACY', 1, '개인정보 수집 및 이용 동의', '기본 개인정보 수집 및 이용 동의 약관입니다.', DATE '2000-01-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM Terms WHERE terms_type = 'PRIVACY' AND version = 1
);

INSERT INTO Terms (terms_type, version, title, content, effective_at, created_at, updated_at)
SELECT 'MARKETING', 1, '마케팅 정보 수신 동의', '기본 마케팅 정보 수신 동의 약관입니다.', DATE '2000-01-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM Terms WHERE terms_type = 'MARKETING' AND version = 1
);
