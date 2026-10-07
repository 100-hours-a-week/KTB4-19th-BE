import http from 'k6/http';
import { check, sleep } from 'k6';

export const BASE_URL = (__ENV.LOAD_TEST_BASE_URL || 'http://localhost:8081').replace(/\/$/, '');
const PASSWORD = __ENV.LOAD_TEST_PASSWORD || 'Loadtest123!';
const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };
const BUILDINGS = ['b01', 'b02', 'b03', 'b04', 'b05'];
const ROOMS = [
  '101', '102', '103', '104',
  '201', '202', '203', '204',
  '301', '302', '303', '304',
  '401', '402', '403', '404',
  '501', '502', '503', '504',
];

export const RESIDENT_EMAILS = BUILDINGS.flatMap(
  (building) => ROOMS.map((room) => `loadtest.${building}.r${room}@zipsai.com`),
);

export function login(email) {
  let response;

  for (let attempt = 1; attempt <= 3; attempt += 1) {
    response = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ email, password: PASSWORD }),
      { headers: JSON_HEADERS, tags: { name: 'login' } },
    );
    if (response.status !== 429) {
      break;
    }
    sleep(attempt);
  }

  const accessToken = response.status === 200 ? response.json('data.accessToken') : null;
  if (!accessToken) {
    throw new Error(`${email} 로그인 실패: HTTP ${response.status}`);
  }
  return accessToken;
}

export function loginResidents(count) {
  return RESIDENT_EMAILS.slice(0, count).map((email) => login(email));
}

export function authHeaders(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}

export function expectStatus(response, status, name) {
  return check(response, { [`${name} HTTP ${status}`]: (res) => res.status === status });
}
