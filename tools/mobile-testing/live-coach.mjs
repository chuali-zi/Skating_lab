import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { artifactDir } from './mobile.mjs';
import { deviceUi } from './ui.mjs';

// Requires a real configured server. Do not run with another test that mutates
// the same development athlete's memory/knowledge while a model request runs.
const ui = await deviceUi();
const saveExisting = process.argv.includes('--save-existing');
const previous = saveExisting ? JSON.parse(await readFile(path.join(artifactDir, 'live-coach-report.json'), 'utf8')) : null;
if (saveExisting) assert.equal(previous.checks[0]?.status, 'passed', 'Resume only an actually verified cloud generation');
const checks = saveExisting ? [previous.checks[0]] : [], base = process.env.LIVE_API_BASE || 'http://127.0.0.1:8080/api/v1';
assert.equal(new URL(base).hostname, '127.0.0.1', 'Only a local development service');
const name = previous?.name || `GLM-Android-${Date.now()}`;
let resumed = previous?.resumed || false, generationSeconds = previous?.generationSeconds ?? null;
// Mobile CLI's app-only screenshot can fail on this emulator. Keep UI control
// through MCP and use ADB's actual full-screen capture for this live flow.
const capture = name => ui.capture(name, { mcpScreenshot: false });
async function check(label, action) {
  try { await action(); checks.push({ name: label, status: 'passed' }); console.log(`PASS ${label}`); }
  catch (error) { checks.push({ name: label, status: 'failed', error: error.message }); await capture('live-coach-failure').catch(() => {}); throw error; }
}
async function go(text, direction = 'up') { await ui.find(text, direction); await ui.tap(text); }
function chipState(nodes, label, ancestors = []) {
  for (const node of nodes || []) {
    if (node.text === label) return [...ancestors, node].reverse().find(parent => parent.checkable || parent.clickable);
    const found = chipState(node.children, label, [...ancestors, node]);
    if (found) return found;
  }
}
try {
  assert.equal((await (await fetch(`${base}/health`)).json()).modelConfigured, true);
  const login = await (await fetch(`${base}/dev/session`, { method: 'POST' })).json();
  const headers = { Authorization: `Bearer ${login.token}` };
  const catalog = await (await fetch(`${base}/catalog/jumps`, { headers })).json();
  if (!saveExisting) await check('native Android submits to the real cloud coach and resumes pending work', async () => {
    await ui.call('mobile_terminate_app', { packageName: 'com.skatingcoach.android' });
    await ui.call('mobile_launch_app', { packageName: 'com.skatingcoach.android' });
    const port = new URL(base).port || '80';
    // ADB reconnects clear reverse mappings, so establish it for every run.
    ui.adb('reverse', `tcp:${port}`, `tcp:${port}`);
    await ui.waitFor('连接服务'); await ui.tap('连接服务', { last: true });
    await ui.fill('服务地址', new URL(base).origin);
    await ui.tap('连接'); await ui.waitFor('已连接');
    await go('从空白方案开始编写'); await ui.tap('准备');
    for (const code of ['2A', '2T']) {
      const option = catalog.find(item => item.code === code);
      const label = `${code} · ${option.label}`;
      await ui.find(label, 'up');
      // The compact MCP JSON drops checked/selected; use its raw hierarchy.
      const raw = await ui.call('mobile_list_elements_on_screen', { format: 'raw' });
      const chip = chipState(JSON.parse(raw.content[0].text).hierarchy, label);
      assert(chip, `Selectable chip for ${code}`);
      if (!chip.checked && !chip.selected) await ui.tap(label);
    }
    await ui.find('说说你的状态与想法');
    await ui.fill('说说你的状态与想法', `${name} 本次合成开发测试：今天2A和2T比较稳定，90秒后容易疲惫。我希望将来学3A，但现在不会。请只用2A、2T给出两种各三组跳跃的训练安排及计划时间，说明不同取舍，不提供未经核实的正式分数。`);
    await ui.find('比赛或训练情境'); await ui.fill('比赛或训练情境', name);
    await go('结合我的情况生成编排');
    const generationStarted = Date.now();
    let progressAt = generationStarted;
    const deadline = Date.now() + 650_000;
    let completed = false;
    while (Date.now() < deadline) {
      const screen = await ui.read();
      if (screen.some(e => e.text === '关闭提示' || e.label === '关闭提示')) throw new Error('Android displayed a service/model error; inspect screenshot');
      if (screen.some(e => e.text === '主编排与失误预案')) { completed = true; break; }
      if (Date.now() - progressAt >= 30_000) { console.log(`Android waiting for cloud result: ${Math.round((Date.now() - generationStarted) / 1000)}s`); progressAt = Date.now(); }
      const busy = screen.some(e => e.text?.startsWith('正在'));
      if (!busy) {
        await ui.find('查看最近一次分析');
        await capture('live-coach-pending');
        await ui.tap('查看最近一次分析');
        resumed = true;
      }
      await new Promise(resolve => setTimeout(resolve, 1200));
    }
    assert(completed, 'Cloud coaching did not finish within the test deadline');
    generationSeconds = (Date.now() - generationStarted) / 1000;
    await ui.find('建议依据'); await capture('live-coach-result');
    await ui.find('使用这份方案'); await capture('live-coach-candidate');
  });

  await check('real model candidate can be applied, edited and saved from Android', async () => {
    if (!saveExisting) await ui.tap('使用这份方案');
    await ui.find('方案名称', 'down', 40);
    if (saveExisting) assert.equal((await ui.field('方案名称')).value, name, 'The same applied cloud draft must still be open');
    else await ui.fill('方案名称', name);
    // Generated rationale/evidence has variable length; the editor may be
    // farther down than the short fixture pages in the offline UI suite.
    await ui.find('保存主编排', 'up', 40); await ui.tap('保存主编排'); await ui.waitFor('方案已保存');
    const plans = await (await fetch(`${base}/plans`, { headers })).json();
    const saved = plans.find(plan => plan.title === name);
    assert(saved);
    assert.equal(saved.body.elements.length, 3);
    assert(saved.body.elements.every(element => element.type === 'SINGLE' && element.jumps.every(code => ['2A', '2T'].includes(code))));
    assert(saved.body.elements.every(element => element.plannedStartSeconds !== null));
    await capture('live-coach-saved');
  });
} catch (error) { console.error(error.message); process.exitCode = 1; }
finally {
  await writeFile(path.join(artifactDir, 'live-coach-report.json'), JSON.stringify({ name, resumed, generationSeconds, resumedAfterHarnessFailure: saveExisting, checks, finishedAt: new Date().toISOString() }, null, 2));
  await ui.close();
}
