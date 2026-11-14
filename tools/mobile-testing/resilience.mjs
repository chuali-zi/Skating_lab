import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { artifactDir } from './mobile.mjs';
import { deviceUi } from './ui.mjs';

const ui = await deviceUi();
const checks = [];
const draft = `MCP-reconnect-${Date.now()}：先求稳，后半程疲惫时请降低难度。`;
const font = ui.adb('shell', 'settings', 'get', 'system', 'font_scale').trim();
async function check(name, run) {
  try { await run(); checks.push({ name, status: 'passed' }); console.log(`PASS ${name}`); }
  catch (error) { checks.push({ name, status: 'failed', error: error.message }); await ui.capture('resilience-failure').catch(() => {}); throw error; }
}
async function go(text, direction = 'up') { await ui.find(text, direction); await ui.tap(text); }
async function connect() { await ui.tap('连接服务', { last: true }); await ui.tap('连接'); }

try {
  await check('restart while offline preserves the draft and reconnects', async () => {
    await ui.hideKeyboard();
    await ui.tap('准备');
    await go('从空白方案开始编写');
    await ui.tap('准备');
    await ui.find('说说你的状态与想法');
    await ui.fill('说说你的状态与想法', draft);
    await ui.call('mobile_terminate_app', { packageName: 'com.skatingcoach.android' });
    ui.adb('reverse', '--remove', 'tcp:8080');
    await ui.call('mobile_launch_app', { packageName: 'com.skatingcoach.android' });
    await ui.waitFor('连接服务');
    await connect();
    await ui.waitFor('关闭提示', 20_000);
    await ui.waitFor('暂时无法连接服务');
    await ui.capture('offline-draft');
    await ui.find('说说你的状态与想法');
    assert.equal((await ui.field('说说你的状态与想法')).value, draft);
    ui.adb('reverse', 'tcp:8080', 'tcp:8080');
    await connect();
    await ui.waitFor('已连接');
    await ui.find('说说你的状态与想法');
    assert.equal((await ui.field('说说你的状态与想法')).value, draft);
    await ui.capture('reconnected-draft');
  });

  const health = await (await fetch('http://127.0.0.1:8080/api/v1/health')).json();
  if (!health.modelConfigured) {
    await check('missing cloud model is reported and user input survives', async () => {
      await go('结合我的情况生成编排');
      await ui.waitFor('服务端尚未配置云端模型', 40_000);
      await ui.capture('model-unavailable');
      await ui.tap('关闭提示');
      await ui.find('说说你的状态与想法', 'down');
      assert.equal((await ui.field('说说你的状态与想法')).value, draft);
    });
  } else checks.push({ name: 'missing cloud model', status: 'skipped', reason: 'A real model is configured; no paid model request made by this test.' });

  await check('landscape rotation retains the editing state', async () => {
    await ui.call('mobile_set_orientation', { orientation: 'landscape' });
    await ui.find('说说你的状态与想法');
    assert.equal((await ui.field('说说你的状态与想法')).value, draft);
    await ui.capture('landscape');
    await ui.call('mobile_set_orientation', { orientation: 'portrait' });
  });

  await check('small screen, large font, dark mode and native keyboard', async () => {
    ui.adb('shell', 'wm', 'size', '720x1280');
    ui.adb('shell', 'wm', 'density', '320');
    ui.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5');
    ui.adb('shell', 'cmd', 'uimode', 'night', 'yes');
    await ui.tap('档案');
    await ui.find('补充一条希望长期记住的信息');
    await ui.fill('补充一条希望长期记住的信息', '小屏幕与大字号测试输入');
    const elements = await ui.read();
    for (const tab of ['准备', '编排', '资料', '档案']) assert(elements.some(e => e.text === tab), `Tab accessible: ${tab}`);
    await ui.waitSystemOverlays();
    await ui.capture('small-large-font-dark');
    await ui.tapElement(await ui.field('补充一条希望长期记住的信息'));
    assert(/mInputShown=true/.test(ui.adb('shell', 'dumpsys', 'input_method')), 'Native IME is really visible');
    await ui.capture('small-keyboard');
    await ui.hideKeyboard();
  });

  await check('no app crash or ANR during device flows', async () => {
    const crashes = ui.adb('logcat', '-b', 'crash', '-d');
    const activity = ui.adb('logcat', '-d', '-s', 'ActivityManager:E');
    await writeFile(path.join(artifactDir, 'device-crashes.log'), crashes + activity);
    assert(!crashes.includes('Process: com.skatingcoach.android'), 'App crash in logcat');
    assert(!activity.includes('ANR in com.skatingcoach.android'), 'App ANR in logcat');
  });
} catch (error) { console.error(error.stack); process.exitCode = 1; }
finally {
  ui.adb('reverse', 'tcp:8080', 'tcp:8080');
  ui.adb('shell', 'wm', 'size', 'reset');
  ui.adb('shell', 'wm', 'density', 'reset');
  if (font === 'null') ui.adb('shell', 'settings', 'delete', 'system', 'font_scale');
  else ui.adb('shell', 'settings', 'put', 'system', 'font_scale', font);
  ui.adb('shell', 'cmd', 'uimode', 'night', 'no');
  await ui.call('mobile_set_orientation', { orientation: 'portrait' }).catch(() => {});
  await writeFile(path.join(artifactDir, 'resilience-report.json'), JSON.stringify({ checks, finishedAt: new Date().toISOString() }, null, 2));
  await ui.close();
}
