-- 로컬 개발용 체험 데이터(local 프로필에서만 적용). 운영 DB에는 넣지 않는다.
-- 로그인: kim.plan@sk.com(경영기획팀), choi.infra@sk.com(조직 담당자) — 인증코드는 서버 로그에 출력됨
INSERT INTO organization (code, name) VALUES
    ('D100', '네트워크인프라부문'),
    ('F1000', '미디어사업부')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_user (email, name, role, organization_id) VALUES
    ('kim.plan@sk.com', '김기획 매니저', 'PLANNER', NULL),
    ('choi.infra@sk.com', '최인프 매니저', 'ORG_MANAGER', (SELECT id FROM organization WHERE code = 'D100')),
    ('park.media@sk.com', '박미디 매니저', 'ORG_MANAGER', (SELECT id FROM organization WHERE code = 'F1000'))
ON CONFLICT (email) DO NOTHING;
