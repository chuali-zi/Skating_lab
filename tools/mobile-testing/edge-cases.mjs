import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { artifactDir } from './mobile.mjs';
import { deviceUi } from './ui.mjs';

const origin = process.env.EDGE_API_ORIGIN || 'http://127.0.0.1:8081';
assert.equal(new URL(origin).hostname, '127.0.0.1');
const deviceOrigin = process.env.EDGE_DEVICE_ORIGIN || origin;
assert.equal(new URL(deviceOrigin).hostname, '127.0.0.1');
const ui = await deviceUi();
const rulesOnly = process.argv.includes('--rules-only');
const requestsOnly = process.argv.includes('--requests-only');
const checks = [];
const name = `QA-${Date.now().toString(36)}`;
const capture = name => ui.capture(name, { mcpScreenshot: false });
const time = '计划执行时间（秒）';
let token;

async function request(endpoint, method = 'GET', body) {
  const response = await fetch(`${origin}/api/v1${endpoint}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert(response.ok, `${method} ${endpoint}: HTTP ${response.status}`);
  return response.json();
}

async function go(label, direction = 'up') {
  await ui.find(label, direction);
  await ui.tap(label);
}

async function openSaved(title) {
  await ui.tap('档案');
  await go('刷新档案');
  await ui.tap('准备');
  await go('已保存方案');
  await go(title);
}

async function check(name, action) {
  try {
    await action();
    checks.push({ name, status: 'passed' });
    console.log(`PASS ${name}`);
  } catch (error) {
    checks.push({ name, status: 'failed', error: error.message });
    await capture(`edge-failure-${checks.length}`).catch(() => {});
    throw error;
  }
}

try {
  token = (await request('/dev/session', 'POST')).token;
  ui.adb('shell', 'am', 'force-stop', 'com.skatingcoach.android');
  ui.adb('shell', 'am', 'start', '-W', '-n', 'com.skatingcoach.android/.MainActivity');
  ui.adb('reverse', `tcp:${new URL(deviceOrigin).port}`, `tcp:${new URL(origin).port}`);
  await ui.waitFor('连接服务', 60_000);
  await ui.tap('连接服务', { last: true });
  if ((await ui.field('服务地址')).value !== deviceOrigin) {
    await ui.fill('服务地址', deviceOrigin);
    assert.equal((await ui.field('服务地址')).value, deviceOrigin);
  }
  await ui.tap('连接');
  await ui.waitFor('已连接', 45_000);

  if (!rulesOnly && !requestsOnly) await check('non-finite pasted time stays visible without breaking draft or API save', async () => {
    await go('从空白方案开始编写');
    await ui.find('方案名称', 'down');
    await ui.fill('方案名称', name);
    await go('添加跳跃行');
    await ui.find('动作或有序连跳', 'down');
    await ui.fill('动作或有序连跳', '2A');
    await ui.find(time);
    await ui.fill(time, 'Infinity');
    await ui.waitFor('请输入有限数字');
    assert.equal((await ui.field(time)).value, 'Infinity');
    await capture('edge-invalid-time');
    await go('保存主编排');
    await ui.waitFor('方案已保存');
    const saved = (await request('/plans')).find(plan => plan.title === name);
    assert(saved);
    assert.equal(saved.body.elements[0].plannedStartSeconds ?? null, null);
  });

  if (!rulesOnly && !requestsOnly) await check('negative time is flagged and decimal time survives overwrite and save', async () => {
    await ui.find(time, 'down');
    // Leading whitespace keeps Mobile CLI from treating the minus as an option.
    await ui.fill(time, ' -0.5');
    await ui.waitFor('计划时间早于节目开始');
    await capture('edge-negative-time');
    await ui.fill(time, '90.50');
    assert.equal((await ui.field(time)).value, '90.50');
    await go('保存主编排');
    await ui.waitFor('方案已保存');
    const saved = (await request('/plans')).find(plan => plan.title === name);
    assert.equal(saved.body.elements[0].plannedStartSeconds, 90.5);
  });

  if (!rulesOnly && !requestsOnly) await check('old recovery is marked after main plan changes and retains the actual prefix', async () => {
    const planned = { id: randomUUID(), type: 'COMBINATION', jumps: ['3S', '2T'], plannedStartSeconds: 25 };
    const completed = { ...planned, type: 'SINGLE', jumps: ['2S'] };
    const main = await request('/plans', 'POST', { title: `${name} 主编排`, kind: 'MAIN', body: { elements: [planned] } });
    const recovery = await request(`/plans/${main.id}/recovery`, 'POST', {
      title: `${name} 降周预案`, expectedRevision: main.revision, completedPrefix: [completed],
      proposedElements: [completed], failureDescription: '第一跳降成 2S，未接第二跳',
    });
    await request(`/plans/${main.id}`, 'PATCH', {
      title: main.title, expectedRevision: main.revision, kind: 'MAIN',
      body: { elements: [{ ...planned, plannedStartSeconds: 75 }] },
    });
    await openSaved(recovery.plan.title);
    await ui.waitFor('这份预案基于旧版');
    await capture('edge-stale-recovery');
    await ui.find('已完成 · 固定');
    assert.equal((await ui.field('动作或有序连跳')).value, '2S');
    await capture('edge-frozen-downgrade');
  });

  if (!requestsOnly) await check('fixture BV changes at the exclusive late-program boundary', async () => {
    const unlimited = { unrestricted: true };
    const ruleSet = {
      id: 'android-boundary-fixture', version: '1', sourceReference: '合成测试规则，非正式赛事',
      baseValues: { '3S': 4.3, '3T': 4.2 }, scoreScale: 2, roundingMode: 'HALF_UP', sequenceMultiplier: 1,
      allApplicableRulesRepresented: true, unresolvedClauses: [],
      bonus: { enabled: true, startsAtSeconds: 60, includeBoundary: false, multiplier: 1.1, eligibleTypes: ['COMBINATION'], awardOrder: 'FIRST' },
      limits: { elements: unlimited, combinations: unlimited, sequences: unlimited, jumpsPerElement: unlimited, jumpOccurrences: {}, jumpOccurrencesComplete: true, requiredJumps: [] },
    };
    const main = await request('/plans', 'POST', {
      title: `${name} 合成规则`, kind: 'MAIN', body: {
        elements: [{ id: randomUUID(), type: 'COMBINATION', jumps: ['3S', '3T'], plannedStartSeconds: 60 }],
        ruleSet, selection: { selectedSingles: [], selectedCombinations: ['3S+3T'] },
      },
    });
    await openSaved(main.title);
    await ui.waitFor('8.50');
    await capture('edge-boundary-60');
    await ui.find(time);
    await ui.tapElement(await ui.field(time));
    ui.adb('shell', 'input', 'keyevent', '123');
    await ui.type('.01');
    await ui.hideKeyboard();
    assert.equal((await ui.field(time)).value, '60.01');
    await ui.waitFor('9.35');
    await capture('edge-boundary-60-01');
  });

  if (!rulesOnly) await check('missing model is reported without losing a multiline user request', async () => {
    assert.equal((await request('/health')).modelConfigured, false);
    const input = '更正：先前写了想练 3A，这次完全不用。\n后半程腿软，先求稳，保留 2A。请说明取舍。';
    await ui.tap('准备');
    await ui.find('说说你的状态与想法');
    const entered = await ui.field('说说你的状态与想法');
    if (entered.value !== input) {
      if (entered.value) await ui.fill('说说你的状态与想法', input);
      else { await ui.tapElement(entered); await ui.type(input); await ui.hideKeyboard(); }
    }
    assert.equal((await ui.field('说说你的状态与想法')).value, input);
    await go('结合我的情况生成编排');
    await ui.waitFor('服务端尚未配置云端模型');
    await capture('edge-model-not-configured');
    await ui.tap('关闭提示');
    await ui.find('说说你的状态与想法', 'down');
    assert.equal((await ui.field('说说你的状态与想法')).value, input);
  });
} catch (error) {
  if (checks.length === 0) {
    checks.push({ name: 'connect native app to isolated backend', status: 'failed', error: error.message });
    await capture('edge-setup-failure').catch(() => {});
  }
  console.error(error.stack);
  process.exitCode = 1;
} finally {
  await writeFile(path.join(artifactDir, 'edge-report.json'), JSON.stringify({ name, checks, finishedAt: new Date().toISOString() }, null, 2));
  await ui.close();
}
