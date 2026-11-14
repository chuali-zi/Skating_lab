import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { artifactDir } from './mobile.mjs';
import { deviceUi } from './ui.mjs';

const apiBase = 'http://127.0.0.1:8080/api/v1';
const ui = await deviceUi();
const testName = `MCP-${new Date().toISOString().replace(/[:.]/g, '-')}`;
const report = { startedAt: new Date().toISOString(), testName, checks: [] };
let token;
async function api(endpoint) {
  const response = await fetch(`${apiBase}${endpoint}`, { headers: { Authorization: `Bearer ${token}` } });
  assert(response.ok, `${endpoint}: HTTP ${response.status}`);
  return response.json();
}
async function check(name, action) {
  try { await action(); report.checks.push({ name, status: 'passed' }); console.log(`PASS ${name}`); }
  catch (error) {
    report.checks.push({ name, status: 'failed', error: error.message });
    await ui.capture('failure').catch(() => {});
    throw error;
  }
}
async function go(text, direction = 'up') { await ui.find(text, direction); await ui.tap(text); }
async function input(label, value, direction = 'up') { await ui.find(label, direction); await ui.fill(label, value); }
let mainPlan, recoveryPlan;

try {
  const health = await (await fetch(`${apiBase}/health`)).json();
  assert.equal(health.status, 'UP');
  const login = await fetch(`${apiBase}/dev/session`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert(login.ok, 'Local dev session required');
  token = (await login.json()).token;

  await check('native login and live backend catalogue', async () => {
    const initial = await ui.read();
    if (!initial.some(e => e.text === '已连接')) {
      await ui.tap('连接服务', { last: true });
      await ui.fill('服务地址', 'http://127.0.0.1:8080');
      await ui.tap('连接');
      await ui.waitFor('已连接');
    }
    await ui.tap('准备');
    await ui.waitFor('可用单跳');
    assert((await api('/catalog/jumps')).length >= 20);
  });

  await check('edit and save two rows with explicit single type and times', async () => {
    await go('从空白方案开始编写');
    await input('方案名称', testName, 'down');
    await go('添加跳跃行');
    await input('动作或有序连跳', '3S', 'down');
    await ui.type('+');
    await ui.type('2T');
    await ui.hideKeyboard();
    assert.equal((await ui.field('动作或有序连跳')).value, '3S+2T');
    await input('计划执行时间（秒）', '20');
    await go('添加跳跃行');
    await ui.find('02');
    await ui.find('添加跳跃行');
    // The newly appended row is the last visible action input.
    let labels = (await ui.read()).filter(e => e.text === '动作或有序连跳');
    if (!labels.length) { await ui.find('动作或有序连跳'); labels = (await ui.read()).filter(e => e.text === '动作或有序连跳'); }
    await ui.fill('动作或有序连跳', '2A', labels.length - 1);
    const times = (await ui.read()).filter(e => e.text === '计划执行时间（秒）');
    await ui.fill('计划执行时间（秒）', '150', times.length - 1);
    await go('保存主编排');
    await ui.waitFor('方案已保存');
    mainPlan = (await api('/plans')).find(p => p.title === testName);
    assert(mainPlan, 'Saved plan should exist in real PostgreSQL-backed API');
    assert.deepEqual(mainPlan.body.elements.map(e => e.type), ['COMBINATION', 'SINGLE']);
    assert.deepEqual(mainPlan.body.elements.map(e => e.jumps), [['3S', '2T'], ['2A']]);
    assert.deepEqual(mainPlan.body.elements.map(e => e.plannedStartSeconds), [20, 150]);
  });

  await check('long-press drag, move buttons and undo keep row identity and time', async () => {
    await go('01', 'down');
    await ui.hideKeyboard();
    const handle = (await ui.read()).find(e => (e.text || e.label || '').startsWith('长按拖动调整第1组'));
    assert(handle, 'First row exposes its drag handle');
    const box = handle.coordinates;
    const x = Math.round(box.x + box.width / 2), y = Math.round(box.y + box.height / 2);
    // MCP has no held-pointer drag tool. ADB supplies the native long-press
    // gesture at the handle just observed through MCP.
    // Allow the shell command's initial long-press wait plus actual movement.
    ui.adb('shell', 'input', 'touchscreen', 'draganddrop', String(x), String(y), String(x), String(y + box.height * 3), '1800');
    await go('01', 'down');
    await ui.find('动作或有序连跳');
    assert.equal((await ui.field('动作或有序连跳')).value, '2A');
    assert.equal((await ui.field('计划执行时间（秒）')).value, '150');
    await go('撤销修改');
    await go('01', 'down'); await ui.find('动作或有序连跳');
    assert.equal((await ui.field('动作或有序连跳')).value, '3S+2T');
    await go('下移');
    await go('01', 'down'); await ui.find('动作或有序连跳');
    assert.equal((await ui.field('动作或有序连跳')).value, '2A');
    await go('撤销修改');
    await go('01', 'down'); await ui.find('动作或有序连跳');
    assert.equal((await ui.field('动作或有序连跳')).value, '3S+2T');
    assert.equal((await ui.field('计划执行时间（秒）')).value, '20');
    await ui.capture('reorder-restored');
  });

  await check('create recovery and freeze the performed prefix', async () => {
    await go('准备失误预案');
    await ui.waitFor('这次实际发生了什么');
    await ui.tap('建立预案');
    await input('动作或有序连跳', '1A', 'down');
    assert.equal((await ui.field('动作或有序连跳')).value, '3S', 'Performed row must be read-only');
    await go('保存失误预案');
    await ui.waitFor('方案已保存');
    recoveryPlan = (await api('/plans')).find(p => p.title === `${testName} · 失误预案`);
    assert(recoveryPlan);
    assert.equal(recoveryPlan.body.sourcePlanId, mainPlan.id);
    assert.deepEqual(recoveryPlan.body.completedPrefix[0].jumps, ['3S']);
    assert.equal(recoveryPlan.body.completedPrefix[0].type, 'SINGLE');
  });

  await check('reopen recovery directly with the correct page and frozen prefix', async () => {
    await ui.tap('准备');
    await go(`${testName} · 失误预案`);
    await ui.waitFor('已完成 · 固定');
    const elements = await ui.read();
    assert(!elements.some(e => e.text === '方案名称'), 'Opening recovery must not display the main editor');
    await ui.capture('recovery-reopened');
  });
} catch (error) { console.error(error.stack); process.exitCode = 1; }
finally {
  report.finishedAt = new Date().toISOString();
  await writeFile(path.join(artifactDir, 'smoke-report.json'), JSON.stringify(report, null, 2));
  await writeFile(path.join(artifactDir, `${testName}-report.json`), JSON.stringify(report, null, 2));
  await ui.close();
}
