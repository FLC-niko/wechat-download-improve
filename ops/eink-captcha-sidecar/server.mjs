#!/usr/bin/env node
// Local-only mock sidecar for the WeRead E-Ink -2041 verification pipeline.
//
// It mimics the integration contract of hailan09/crackTCaptcha
// (`crack-tcaptcha serve`): POST /solve -> {ok, ticket, randstr, error, attempts}.
// This mock never contacts Tencent or WeRead and never solves a real captcha;
// it only lets us exercise our own verification page / complete route locally.
//
// Usage:
//   node ops/eink-captcha-sidecar/server.mjs [--host 127.0.0.1] [--port 8765]
//
// Then point the service config (or env) at the shim for local joint debugging:
//   weread.eink.human_verify.captcha_script_url: "http://127.0.0.1:8765/TCaptcha.js"
//   # or WEREAD_EINK_HUMAN_VERIFY_CAPTCHA_SCRIPT_URL=http://127.0.0.1:8765/TCaptcha.js
// Revert the config (or restart without the env) to go back to the official script.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { randomBytes, randomInt } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const options = parseArgs(process.argv.slice(2));
const host = options.host ?? '127.0.0.1';
const port = Number(options.port ?? 8765);

let solveCount = 0;

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--host' || arg === '--port') {
      parsed[arg.slice(2)] = argv[i + 1];
      i += 1;
    } else if (arg.startsWith('--host=')) {
      parsed.host = arg.slice(7);
    } else if (arg.startsWith('--port=')) {
      parsed.port = arg.slice(7);
    }
  }
  return parsed;
}

function sendJson(response, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-SK',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  });
  response.end(body);
}

function sendFile(response, status, contentType, body) {
  response.writeHead(status, {
    'Content-Type': contentType,
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  response.end(body);
}

function readBody(request) {
  return new Promise((resolve) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });
}

function mockTicket() {
  const stamp = Date.now().toString(36);
  const entropy = randomBytes(8).toString('hex');
  return `local-sidecar-${stamp}-${entropy}`;
}

function mockRandstr() {
  const alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  let value = '';
  for (let i = 0; i < 10; i += 1) value += alphabet[randomInt(alphabet.length)];
  return `@${value}`;
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? `${host}:${port}`}`);

  if (request.method === 'OPTIONS') {
    sendJson(response, 204, {});
    return;
  }

  if (request.method === 'GET' && url.pathname === '/health') {
    sendJson(response, 200, { status: 'ok', mode: 'local-mock', solve_count: solveCount });
    return;
  }

  if (request.method === 'POST' && url.pathname === '/solve') {
    let body = {};
    try {
      const raw = await readBody(request);
      body = raw ? JSON.parse(raw) : {};
    } catch {
      sendJson(response, 400, { ok: false, ticket: '', randstr: '', error: 'invalid json', attempts: 0 });
      return;
    }
    const appid = body.appid || body.app_id;
    if (!appid) {
      sendJson(response, 400, { ok: false, ticket: '', randstr: '', error: 'missing appid', attempts: 0 });
      return;
    }
    solveCount += 1;
    sendJson(response, 200, {
      ok: true,
      ticket: mockTicket(),
      randstr: mockRandstr(),
      error: '',
      attempts: 1,
      _cost_s: 0,
      _mock: true,
    });
    return;
  }

  if (request.method === 'GET' && url.pathname === '/TCaptcha.js') {
    const shim = await readFile(join(here, 'mock-tcaptcha.js'), 'utf8');
    const head = `window.__MOCK_TCAPTCHA_SOLVE_URL__ = "http://${host}:${port}/solve";\n`;
    sendFile(response, 200, 'application/javascript; charset=utf-8', head + shim);
    return;
  }

  if (request.method === 'GET' && (url.pathname === '/tcap2_loader.html' || url.pathname === '/')) {
    const page = await readFile(join(here, 'tcap2_loader.html'), 'utf8');
    sendFile(response, 200, 'text/html; charset=utf-8', page);
    return;
  }

  sendJson(response, 404, { status: 'error', msg: `not found: ${request.method} ${url.pathname}` });
});

server.listen(port, host, () => {
  console.log(`[eink-captcha-sidecar] local mock listening on http://${host}:${port}`);
  console.log(`[eink-captcha-sidecar] loader page: http://${host}:${port}/tcap2_loader.html`);
  console.log(`[eink-captcha-sidecar] shim script: http://${host}:${port}/TCaptcha.js`);
});
