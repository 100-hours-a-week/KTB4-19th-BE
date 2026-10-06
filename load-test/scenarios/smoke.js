import http from 'k6/http';

import { BASE_URL, RESIDENT_EMAILS, authHeaders, expectStatus, login } from '../lib/zipsai.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
  },
};

export default function () {
  const headers = authHeaders(login(RESIDENT_EMAILS[0]));

  const list = http.get(`${BASE_URL}/api/v1/residents/me/conversations?size=20`, { headers });
  expectStatus(list, 200, '대화 목록 조회');

  const started = http.post(
    `${BASE_URL}/api/v1/residents/me/conversations`,
    JSON.stringify({ content: '안방 천장에서 물이 떨어져요', attachmentIds: [] }),
    { headers },
  );
  expectStatus(started, 201, '대화 시작');

  const conversationId = started.json('data.conversationId');
  const sent = http.post(
    `${BASE_URL}/api/v1/residents/me/conversations/${conversationId}/messages`,
    JSON.stringify({ content: '안방 천장 가운데요', attachmentIds: [] }),
    { headers },
  );
  expectStatus(sent, 201, '메시지 전송');

  const messages = http.get(`${BASE_URL}/api/v1/residents/me/conversations/${conversationId}/messages`, { headers });
  expectStatus(messages, 200, '메시지 조회');
}
