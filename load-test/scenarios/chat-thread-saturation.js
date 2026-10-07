import http from 'k6/http';
import { Rate, Trend } from 'k6/metrics';

import { BASE_URL, RESIDENT_EMAILS, authHeaders, expectStatus, loginResidents } from '../lib/zipsai.js';

const CHAT_STAGES = (__ENV.CHAT_STAGES || '8,12,16,20,24,32').split(',').map(Number);
const STAGE_DURATION = __ENV.STAGE_DURATION || '90s';
const RAMP_DURATION = __ENV.RAMP_DURATION || '20s';
const BROWSE_RPS = Number(__ENV.BROWSE_RPS || 5);
const COMPLAINT_RATIO = Number(__ENV.COMPLAINT_RATIO || 0.7);

const COMPLAINT_MESSAGES = [
  '안방 천장에서 물이 떨어져요',
  '보일러가 작동하지 않아요',
  '화장실 배수구가 막혔어요',
  '현관 도어락이 고장났어요',
  '복도 전등이 계속 깜빡여요',
];
const KNOWLEDGE_MESSAGES = [
  '분리수거는 어디서 하나요?',
  '주차 등록은 어떻게 하나요?',
  '세탁실 이용 시간 알려주세요',
];

const chatDuration = new Trend('chat_send_duration', true);
const chatFailed = new Rate('chat_send_failed');
const browseDuration = new Trend('browse_duration', true);
const browseFailed = new Rate('browse_failed');

const chatStages = CHAT_STAGES.flatMap((target) => [
  { target, duration: RAMP_DURATION },
  { target, duration: STAGE_DURATION },
]);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    chat: {
      executor: 'ramping-arrival-rate',
      exec: 'sendChat',
      startRate: CHAT_STAGES[0],
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: Number(__ENV.MAX_VUS || 2000),
      stages: chatStages,
    },
    browse: {
      executor: 'constant-arrival-rate',
      exec: 'browse',
      rate: BROWSE_RPS,
      timeUnit: '1s',
      duration: totalDuration(chatStages),
      preAllocatedVUs: 20,
      maxVUs: 500,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { tokens: loginResidents(RESIDENT_EMAILS.length) };
}

export function sendChat(data) {
  const isComplaint = Math.random() < COMPLAINT_RATIO;
  const messages = isComplaint ? COMPLAINT_MESSAGES : KNOWLEDGE_MESSAGES;
  const response = http.post(
    `${BASE_URL}/api/v1/residents/me/conversations`,
    JSON.stringify({ content: pick(messages), attachmentIds: [] }),
    {
      headers: authHeaders(pick(data.tokens)),
      tags: { name: isComplaint ? 'chat_complaint' : 'chat_knowledge' },
      timeout: '60s',
    },
  );

  chatDuration.add(response.timings.duration);
  chatFailed.add(!expectStatus(response, 201, '채팅 전송'));
}

export function browse(data) {
  const response = http.get(
    `${BASE_URL}/api/v1/residents/me/conversations?size=20`,
    { headers: authHeaders(pick(data.tokens)), tags: { name: 'browse_conversations' }, timeout: '60s' },
  );

  browseDuration.add(response.timings.duration);
  browseFailed.add(!expectStatus(response, 200, '대화 목록 조회'));
}

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function totalDuration(stages) {
  const seconds = stages.reduce((sum, stage) => sum + toSeconds(stage.duration), 0);
  return `${seconds}s`;
}

function toSeconds(duration) {
  const value = Number.parseInt(duration, 10);
  return duration.endsWith('m') ? value * 60 : value;
}
