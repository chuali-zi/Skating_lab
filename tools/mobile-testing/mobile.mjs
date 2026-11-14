import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

export const workspace = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const artifactDir = path.join(workspace, 'tmp/mobile-testing');

// A real MCP stdio connection, scoped to this test process. No global IDE config,
// cloud device account, backend credentials, or listening network port is needed.
export async function connectMobile() {
  const sdk = process.env.ANDROID_HOME || path.join(workspace, 'android/.tools/sdk');
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [path.join(workspace, 'tools/mobile-testing/node_modules/@mobilenext/mobile-mcp/lib/index.js')],
    env: {
      ANDROID_HOME: sdk,
      ANDROID_SDK_ROOT: sdk,
      MOBILEMCP_DISABLE_TELEMETRY: '1',
      MOBILECLI_HOME: path.join(workspace, '.tools/mobilecli'),
      ...(process.env.LOCALAPPDATA ? { LOCALAPPDATA: process.env.LOCALAPPDATA } : {}),
      ...(process.env.TEMP ? { TEMP: process.env.TEMP, TMP: process.env.TEMP } : {}),
    },
    stderr: 'pipe',
  });
  await mkdir(artifactDir, { recursive: true });
  const client = new Client({ name: 'skating-coach-device-check', version: '0.1.0' });
  await client.connect(transport);
  transport.stderr?.on('data', () => {});
  return {
    client,
    async call(name, args = {}) {
      const result = await client.callTool({ name, arguments: args }, undefined, { timeout: 180_000 });
      if (result.isError) throw new Error(JSON.stringify(result.content));
      return result;
    },
    close: () => client.close(),
  };
}

export function resultText(result) {
  return result.content.filter(item => item.type === 'text').map(item => item.text).join('\n');
}

async function main() {
  const [name = 'tools', ...flags] = process.argv.slice(2);
  const args = {};
  for (let i = 0; i < flags.length; i += 2) {
    if (!flags[i].startsWith('--') || flags[i + 1] === undefined) throw new Error('Use --argument value pairs');
    const key = flags[i].slice(2);
    const value = flags[i + 1];
    if (key === 'args-file') Object.assign(args, JSON.parse(await readFile(value, 'utf8')));
    else args[key] = ['true', 'false'].includes(value) ? value === 'true' : value;
  }
  const mcp = await connectMobile();
  try {
    if (name === 'tools') {
      const { tools } = await mcp.client.listTools();
      console.log(JSON.stringify(tools.map(({ name, inputSchema }) => ({ name, inputSchema })), null, 2));
    } else {
      const { tools } = await mcp.client.listTools();
      const schema = tools.find(tool => tool.name === name)?.inputSchema;
      if (!schema) throw new Error(`Unknown MCP tool: ${name}`);
      for (const [key, value] of Object.entries(args)) {
        if (['number', 'integer'].includes(schema.properties?.[key]?.type)) args[key] = Number(value);
      }
      const result = await mcp.call(name, args);
      for (const item of result.content) {
        if (item.type === 'image') {
          const file = path.join(artifactDir, `screen-${Date.now()}.${item.mimeType === 'image/jpeg' ? 'jpg' : 'png'}`);
          await writeFile(file, Buffer.from(item.data, 'base64'));
          console.log(JSON.stringify({ screenshot: file }));
        } else if (item.type === 'text') console.log(item.text);
      }
    }
  } finally { await mcp.close(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
