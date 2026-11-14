import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { artifactDir } from './mobile.mjs';
import { deviceUi } from './ui.mjs';

const ui = await deviceUi();
const prefix = `MCP-upload-${Date.now()}`;
const checks = [], created = [], deviceFiles = [];
const base = 'http://127.0.0.1:8080/api/v1';
const sessionResponse = await fetch(`${base}/dev/session`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
assert(sessionResponse.ok, 'Local development session required');
const headers = { Authorization: `Bearer ${(await sessionResponse.json()).token}` };
async function get(endpoint) { const response = await fetch(base + endpoint, { headers }); assert(response.ok); return response.json(); }
async function check(name, action) {
  try { await action(); checks.push({ name, status: 'passed' }); console.log(`PASS ${name}`); }
  catch (error) { checks.push({ name, status: 'failed', error: error.message }); await ui.capture('upload-failure').catch(() => {}); throw error; }
}
async function poll(action) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) { const result = await action(); if (result) return result; await new Promise(resolve => setTimeout(resolve, 400)); }
  throw new Error('Uploaded source did not reach its expected processing state');
}
async function pick(fileName) {
  const target = `/sdcard/Download/${fileName}`;
  ui.adb('push', path.join(artifactDir, fileName), target);
  deviceFiles.push(target);
  await ui.tap('资料');
  await ui.find('选择文件', 'down'); await ui.tap('选择文件');
  await ui.waitFor('Show roots'); await ui.tap('Show roots');
  await ui.tap('Downloads', { last: true });
  await ui.find(fileName); await ui.capture('system-file-picker');
  await ui.tap(fileName);
  await ui.waitFor('资料已上传', 30_000);
  const source = await poll(async () => (await get('/knowledge')).items.find(item => item.title === fileName));
  created.push(source.id);
  return source;
}

try {
  await check('native file picker uploads real UTF-8 bytes and retains source text', async () => {
    const fileName = `${prefix}.txt`;
    const content = `${prefix} 系统文件选择器自测资料\n合成训练笔记：后半程疲惫时优先完成质量。\n仅用于自测，不是比赛规则。`;
    await writeFile(path.join(artifactDir, fileName), content, 'utf8');
    const source = await pick(fileName);
    const detail = await poll(async () => {
      const detail = await get(`/knowledge/${source.id}`);
      return ['ready', 'partial'].includes(detail.source.status) && detail;
    });
    assert(detail.blocks.some(block => block.text.includes('后半程疲惫时优先完成质量')));
    assert(detail.blocks.every(block => block.locator));
    await ui.find('刷新处理进度'); await ui.tap('刷新处理进度');
    await ui.find(fileName); await ui.find('查看内容与来源'); await ui.tap('查看内容与来源');
    await ui.waitFor('后半程疲惫时优先完成质量'); await ui.capture('uploaded-document');
    await ui.press('BACK');
  });

  await check('synthetic video upload reports visual analysis as unavailable', async () => {
    const fileName = `${prefix}.mp4`;
    // A generated one-second color clip, never a user's training video.
    execFileSync('ffmpeg', ['-y', '-f', 'lavfi', '-i', 'color=c=blue:s=320x240:r=10', '-t', '1', '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-metadata', 'title=Synthetic MCP upload test', path.join(artifactDir, fileName)], { windowsHide: true, stdio: 'ignore' });
    const source = await pick(fileName);
    const detail = await poll(async () => {
      const detail = await get(`/knowledge/${source.id}`);
      return !['queued', 'processing'].includes(detail.source.status) && detail;
    });
    assert.equal(detail.source.status, 'awaiting_visual', 'Without visual provider the clip must not be called understood');
    assert.equal(detail.blocks.length, 0, 'Container metadata must not become athlete evidence');
    await ui.find('刷新处理进度'); await ui.tap('刷新处理进度');
    await ui.find(fileName); await ui.find('查看内容与来源'); await ui.tap('查看内容与来源');
    await ui.waitFor('目前不能据此判断动作');
    await ui.capture('uploaded-video');
    await ui.press('BACK');
  });
} catch (error) { console.error(error.stack); process.exitCode = 1; }
finally {
  // Only remove IDs returned for this run's uniquely named generated fixtures.
  for (const id of created) {
    const detail = await get(`/knowledge/${id}`);
    assert(detail.source.title.startsWith(prefix));
    const response = await fetch(`${base}/knowledge/${id}`, { method: 'DELETE', headers });
    assert(response.ok, 'Fixture cleanup');
  }
  for (const file of deviceFiles) ui.adb('shell', 'rm', file);
  await writeFile(path.join(artifactDir, 'upload-report.json'), JSON.stringify({ prefix, checks, finishedAt: new Date().toISOString() }, null, 2));
  await ui.close();
}
