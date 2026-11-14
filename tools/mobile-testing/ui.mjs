import { connectMobile, resultText, artifactDir, workspace } from './mobile.mjs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

export async function deviceUi(device = process.env.MOBILE_DEVICE || 'skating_api36') {
  const mcp = await connectMobile();
  const call = (name, args = {}) => mcp.call(name, { device, ...args });
  const adbPath = path.join(process.env.ANDROID_HOME || path.join(workspace, 'android/.tools/sdk'), 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const adbSerial = process.env.ADB_SERIAL || 'emulator-5554';
  const adb = (...args) => execFileSync(adbPath, ['-s', adbSerial, ...args], { encoding: 'utf8', windowsHide: true });
  const connectedName = adbSerial.startsWith('emulator-') ? adb('emu', 'avd', 'name').split(/\r?\n/)[0].trim() : adbSerial;
  if (connectedName !== device) { await mcp.close(); throw new Error(`MCP target ${device} does not match ADB target ${connectedName}`); }
  const ui = {
    call, adb, close: mcp.close,
    async read() {
      const text = resultText(await call('mobile_list_elements_on_screen', { format: 'json' }));
      return JSON.parse(text.slice(text.indexOf('[')));
    },
    async tapElement(element) {
      const b = element.coordinates;
      await ui.waitSystemOverlays();
      await call('mobile_click_on_screen_at_coordinates', { x: Math.round(b.x + b.width / 2), y: Math.round(b.y + b.height / 2) });
      await new Promise(resolve => setTimeout(resolve, 250));
    },
    async tap(text, { last = false } = {}) {
      await ui.hideKeyboard();
      await ui.waitSystemOverlays();
      let previous;
      for (let attempt = 0; attempt < 10; attempt++) {
        const screen = await ui.read();
        const matches = screen.filter(e => !e.type.endsWith('EditText') && (e.text === text || e.label === text));
        const target = last ? matches.at(-1) : matches[0];
        const bounds = target && JSON.stringify(target.coordinates);
        if (target && bounds === previous && !screen.some(e => e.text?.startsWith('正在'))) {
          await ui.tapElement(target); return;
        }
        previous = bounds;
        await new Promise(resolve => setTimeout(resolve, 250));
      }
      throw new Error(`Visible stable element not found: ${text}`);
    },
    async waitFor(text, timeout = 12_000) {
      const deadline = Date.now() + timeout;
      while (Date.now() < deadline) {
        const elements = await ui.read();
        if (elements.some(e => e.text?.includes(text) || e.label?.includes(text))) return elements;
        await new Promise(resolve => setTimeout(resolve, 600));
      }
      throw new Error(`Timed out waiting for: ${text}`);
    },
    async field(label, index = 0) {
      const all = await ui.read();
      const labels = all.filter(e => e.text === label);
      const target = labels[index];
      if (!target) throw new Error(`Visible input label not found: ${label} (${index})`);
      const field = all.find(e => e.type.endsWith('EditText') && target.coordinates.y >= e.coordinates.y - 5 && target.coordinates.y < e.coordinates.y + e.coordinates.height && target.coordinates.x >= e.coordinates.x);
      if (!field) throw new Error(`Input control not found: ${label}`);
      return field;
    },
    async fill(label, text, index = 0) {
      await ui.tapElement(await ui.field(label, index));
      ui.adb('shell', 'input', 'keycombination', '113', '29');
      await call('mobile_type_keys', { text });
      await ui.hideKeyboard();
    },
    async hideKeyboard() {
      // Mobile CLI 1.0.23 snapshots omit the IME overlay on this AOSP image.
      // Check the actual IME state instead of tapping through hidden keyboard keys.
      if (/mInputShown=true/.test(adb('shell', 'dumpsys', 'input_method'))) {
        adb('shell', 'input', 'keyevent', '4');
        await new Promise(resolve => setTimeout(resolve, 600));
      }
    },
    async waitSystemOverlays() {
      // Unicode input uses the Android clipboard. Its temporary system overlay
      // intercepts taps but is absent from Mobile CLI's app-only snapshot.
      const deadline = Date.now() + 15_000;
      while (Date.now() < deadline) {
        const windows = adb('shell', 'dumpsys', 'window', 'windows').split(/(?=  Window #)/);
        if (!windows.some(window => window.includes('ClipboardOverlay') && window.includes('isVisible=true'))) return;
        await new Promise(resolve => setTimeout(resolve, 400));
      }
      throw new Error('Android clipboard overlay still obscures the app');
    },
    async type(text) { await call('mobile_type_keys', { text }); },
    async press(button) { await call('mobile_press_button', { button }); },
    async swipe(direction = 'up', distance = 800) { await call('mobile_swipe_on_screen', { direction, distance: Number(distance) }); },
    async find(text, direction = 'up', attempts = 12) {
      for (let n = 0; n < attempts; n++) {
        const elements = await ui.read();
        const target = elements.find(e => !e.type.endsWith('EditText') && (e.text === text || e.label === text) && e.coordinates.height >= 35);
        if (target) return target;
        await ui.swipe(direction);
      }
      throw new Error(`Could not scroll to: ${text}`);
    },
    async capture(name) {
      if (!/^[a-z0-9_-]+$/i.test(name)) throw new Error('Invalid artifact name');
      const screenshot = path.join(artifactDir, `${name}.png`);
      await call('mobile_save_screenshot', { saveTo: path.join(artifactDir, `${name}-mcp.png`) });
      await writeFile(screenshot, execFileSync(adbPath, ['-s', adbSerial, 'exec-out', 'screencap', '-p'], { windowsHide: true, maxBuffer: 16 * 1024 * 1024 }));
      await writeFile(path.join(artifactDir, `${name}.json`), JSON.stringify(await ui.read(), null, 2));
      return screenshot;
    },
    async inspect() {
      const elements = await ui.read();
      console.log(JSON.stringify(elements.filter(e => e.text || e.label || e.type.endsWith('EditText')).map(e => ({ ref: e.ref, type: e.type.split('.').at(-1), text: e.text || e.label, value: e.value, bounds: e.coordinates, checked: e.checked })), null, 2));
    },
  };
  return ui;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const ui = await deviceUi();
  try {
    const [action = 'inspect', ...args] = process.argv.slice(2);
    if (!['inspect', 'tap', 'fill', 'type', 'press', 'swipe', 'find', 'capture', 'hideKeyboard'].includes(action)) throw new Error('Unknown UI action');
    const result = await ui[action](...args);
    if (result) console.log(JSON.stringify(result));
  } finally { await ui.close(); }
}
