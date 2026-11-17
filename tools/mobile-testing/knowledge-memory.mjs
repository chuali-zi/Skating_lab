import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { artifactDir } from './mobile.mjs';
import { deviceUi } from './ui.mjs';

const ui = await deviceUi();
const name = `QA-${Date.now().toString(36)}`;
const knowledgeOnly = process.argv.includes('--knowledge-only');
const checks = [];
const base = process.env.EDGE_API_ORIGIN ? `${process.env.EDGE_API_ORIGIN}/api/v1` : 'http://127.0.0.1:8080/api/v1';
assert.equal(new URL(base).hostname, '127.0.0.1');
const session = await (await fetch(`${base}/dev/session`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).json();
const headers = { Authorization: `Bearer ${session.token}` };
async function get(endpoint) { const response = await fetch(base + endpoint, { headers }); assert(response.ok); return response.json(); }
async function check(label, run) {
  try { await run(); checks.push({ name: label, status: 'passed' }); console.log(`PASS ${label}`); }
  catch (error) { checks.push({ name: label, status: 'failed', error: error.message }); await ui.capture('profile-failure').catch(() => {}); throw error; }
}
async function poll(run, timeout = 20_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const result = await run(); if (result) return result; await new Promise(resolve => setTimeout(resolve, 350)); }
  throw new Error('Backend state did not converge');
}
async function memoryAction(statement, action, direction = 'up') {
  await ui.hideKeyboard();
  await ui.waitSystemOverlays();
  await ui.find(statement, direction);
  for (let attempt = 0; attempt < 4; attempt++) {
    const nodes = await ui.read();
    const title = nodes.find(node => node.text === statement && !node.type.endsWith('EditText'));
    assert(title, 'The generated test memory must be visible before using its controls');
    const cutoff = nodes.find(node => node.text?.startsWith('本人描述 ·') && node.coordinates.y > title.coordinates.y);
    const nextCard = cutoff && nodes.find(node => node.text?.startsWith('本人描述 ·') && node.coordinates.y > cutoff.coordinates.y);
    const actions = nodes.filter(node => node.text === action && node.coordinates.y > title.coordinates.y
      && (!nextCard || node.coordinates.y < nextCard.coordinates.y));
    if (actions.length === 1) { await ui.tapElement(actions[0]); return; }
    await ui.swipe('up', 210);
  }
  throw new Error(`Could not identify ${action} on the generated memory`);
}
let memory, document;
const original = `${name}：后半程容易疲惫，优先稳定完成。`;
const corrected = `${name}：最近后半程改善，仍偏好完成质量。`;
const documentText = `${name} 自测训练笔记\n周三只练习稳定落冰。特殊要求：后半程感到疲惫时，可以放弃尚未完成的高难度连跳。\n这只是测试资料，不是正式比赛规则。`;

try {
  if (knowledgeOnly) {
    ui.adb('shell', 'am', 'start', '-W', '-n', 'com.skatingcoach.android/.MainActivity');
    const screen = await ui.waitFor('SKATING LAB', 60_000);
    if (!screen.some(node => node.text === '已连接')) {
      await ui.tap('连接服务', { last: true });
      const expectedOrigin = process.env.EDGE_DEVICE_ORIGIN || new URL(base).origin;
      assert.equal((await ui.field('服务地址')).value, expectedOrigin, 'Test app must use the isolated backend');
      await ui.tap('连接');
      await ui.waitFor('已连接', 45_000);
    }
  }
  if (!knowledgeOnly) await check('memory create, correct, and trace the corrected source', async () => {
    await ui.hideKeyboard();
    await ui.tap('档案');
    await ui.fill('补充一条希望长期记住的信息', original);
    await ui.tap('记住这条描述');
    memory = await poll(async () => (await get('/memory')).items.find(item => item.statement === original));
    await memoryAction(original, '纠正');
    assert.equal((await ui.field('应该如何理解')).value, original);
    await ui.fill('应该如何理解', corrected);
    await ui.tap('保存更正');
    const updated = await poll(async () => (await get('/memory')).items.find(item => item.id === memory.id && item.statement === corrected));
    assert(updated.revision > memory.revision);
    memory = updated;
    await memoryAction(corrected, '查看来源');
    await ui.waitFor('这条记忆的来源');
    assert.equal((await get(`/memory/${memory.id}/source`)).content, corrected);
    await ui.capture('memory-correction');
    await ui.press('BACK');
  });

  if (!knowledgeOnly) await check('forget removes active memory and its retrievable source', async () => {
    await memoryAction(corrected, '忘记');
    assert((await ui.waitFor('忘记这条')).some(node => node.text?.includes(corrected)));
    await ui.capture('memory-forget-confirmation');
    await ui.tap('忘记这条');
    await poll(async () => !(await get('/memory')).items.some(item => item.id === memory.id));
    const source = await fetch(`${base}/memory/${memory.id}/source`, { headers });
    assert([404, 410].includes(source.status), `Forgotten source should be unavailable, got ${source.status}`);
    await ui.capture('memory-forgotten');
  });

  await check('free-form Chinese document ingest, retrieval and source inspection', async () => {
    await ui.tap('资料');
    await ui.fill('资料标题（可选）', name);
    await ui.fill('直接粘贴或描述资料', documentText);
    await ui.find('提交文字资料'); await ui.tap('提交文字资料');
    document = await poll(async () => (await get('/knowledge')).items.find(item => item.title === name && ['ready', 'partial'].includes(item.status)));
    await ui.find('从资料里查找');
    await ui.fill('从资料里查找', name);
    await ui.find('搜索资料');
    await ui.tap('搜索资料');
    const search = await poll(async () => {
      const result = await get(`/knowledge/search?q=${encodeURIComponent(name)}`);
      return result.items.some(item => item.sourceId === document.id) && result;
    });
    assert(search.items.find(item => item.sourceId === document.id).locator);
    await ui.find(name); await ui.tap(name);
    await ui.waitFor('自测训练笔记');
    const detail = await get(`/knowledge/${document.id}`);
    assert(detail.blocks.some(block => block.text.includes('高难度连跳')));
    await ui.capture('knowledge-evidence');
    await ui.press('BACK');
  });

  await check('withdraw only the generated test document and exclude it from search', async () => {
    await ui.find('我的资料', 'down');
    const title = await ui.find(name);
    let elements = await ui.read();
    let withdraw = elements.find(e => e.text === '撤回' && e.coordinates.y > title.coordinates.y);
    if (!withdraw) { await ui.swipe('up', 300); elements = await ui.read(); withdraw = elements.find(e => e.text === '撤回'); }
    assert(withdraw, 'Test document withdrawal button');
    await ui.tapElement(withdraw);
    const dialog = await ui.waitFor('撤回这份资料？');
    assert(dialog.some(e => e.text?.includes(name)), 'Never confirm a different document');
    await ui.tap('撤回');
    await poll(async () => !(await get('/knowledge')).items.some(item => item.id === document.id));
    const search = await get(`/knowledge/search?q=${encodeURIComponent(name)}`);
    assert(!search.items.some(item => item.sourceId === document.id));
    await ui.capture('knowledge-withdrawn');
  });
} catch (error) { console.error(error.stack); process.exitCode = 1; }
finally {
  await writeFile(path.join(artifactDir, 'knowledge-memory-report.json'), JSON.stringify({ name, checks, finishedAt: new Date().toISOString() }, null, 2));
  await ui.close();
}
