import assert from 'node:assert/strict';
import { deviceUi } from './ui.mjs';

// Use a dedicated emulator bound to an isolated local development service.
// Run verify-public-skating.ps1 first to supply real, fully processed clips.
const ui = await deviceUi();
try {
  if (!(await ui.read()).some(e => e.text === '已连接')) {
    await ui.tap('连接服务', { last: true });
    await ui.tap('连接');
    await ui.waitFor('已连接', 20000);
  }
  await ui.tap('资料');
  await ui.tap('问滑冰教练');
  await ui.fill('你的问题', '3T< BASE');
  await ui.tap('查证并回答');
  await ui.waitFor('3.36');
  assert((await ui.waitFor('PDF p.3')).some(e => e.text?.includes('PDF p.3')));
  await ui.capture('expert-rule-page', { mcpScreenshot: false });
  await ui.tap('返回资料');
  await ui.tap('视频观察与复核');
  await ui.waitFor('历史片段');
  const history = await ui.waitFor('已生成结果', 20000);
  const completed = history.find(e => e.text?.includes('· 已生成结果'));
  assert(completed, 'Expected an available completed clip');
  await ui.tapElement(completed);
  await ui.waitFor('按采样帧理解');
  const first = (await ui.waitFor('第 1 帧')).find(e => e.text?.startsWith('第 1 帧'));
  await ui.tapElement(first);
  await ui.waitFor('第 1 帧');
  await ui.find('确认纠正');
  await ui.tap('确认纠正');
  await ui.find('用于主方案的失误预案');
  await ui.capture('expert-human-review', { mcpScreenshot: false });
  console.log('PASS Android expert answer with PDF page, private video detail, evidence frame and confirmed review');
} finally {
  await ui.close();
}
