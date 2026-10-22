// E-Ink captcha browser solver daemon.
//
// Contract (same as the old crack-tcaptcha sidecar):
//   POST /solve  {"appid","retries","entry_url"}  (X-SK auth when SOLVER_SK set)
//     -> 200 {"ok":bool,"attempts":N,"ticket","randstr","error"?,"_cost_s"}
//     -> 401 {"ok":false,"error":"unauthorized"}
//   GET  /health -> {"status":"ok",...}
//
// Flow: open the verify page in a real (headless) Chrome over CDP, click the
// captcha button, solve image_select rounds (local cn-clip picker), read the
// ticket straight from the cap_union_new_verify response. The page's own
// /complete POST is blocked so the caller remains the single completer.
import fs from "fs";
import http from "http";
import path from "path";
import { spawn, execFileSync } from "child_process";
import WebSocket from "ws";

const PORT = parseInt(process.env.SOLVER_PORT || "9991", 10);
const DEBUG_PORT = parseInt(process.env.SOLVER_DEBUG_PORT || "9333", 10);
const CLIP_URL = process.env.CLIP_URL || "http://127.0.0.1:9993/pick";
const SK = (process.env.SOLVER_SK || "").trim();
const CHROME = process.env.CHROME_PATH ||
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PROFILE_DIR = process.env.CHROME_PROFILE || "/tmp/eink-solver-chrome";
const HEADLESS = (process.env.HEADLESS || "1") === "1";
const MAX_ROUNDS = parseInt(process.env.SOLVER_MAX_ROUNDS || "12", 10);
const SLOW_PACE = (process.env.SLOW_PACE || "0") === "1";
const think = async (a, b) => {
  if (!SLOW_PACE) { await sleep(a); return; }
  await sleep(a + Math.random() * (b - a));
};
const SOLVE_BUDGET_MS = parseInt(process.env.SOLVER_BUDGET_MS || "60000", 10);
const RECYCLE_SOLVES = parseInt(process.env.SOLVER_RECYCLE_SOLVES || "30", 10);
// 升级题型“依次点击：”：sprite 剪影序列匹配的最低接受分，以及单次解题内允许的 seq 轮数。
const SEQ_MIN_SCORE = parseFloat(process.env.SOLVER_SEQ_MIN_SCORE || "0.62");
const SEQ_MAX_ROUNDS = parseInt(process.env.SOLVER_SEQ_MAX_ROUNDS || "3", 10);
// “包含文字”题低置信时不点击、换一组；超过上限快速失败（误点会抬高风控）。
const TEXT_MAX_SKIPS = parseInt(process.env.SOLVER_TEXT_MAX_SKIPS || "3", 10);
const DEBUG_DIR = process.env.DEBUG_DIR || "/tmp/eink-debug";
const CONST_GRID = [
  { id: 1, range: [0, 34, 220, 254] },
  { id: 2, range: [226, 34, 446, 254] },
  { id: 3, range: [452, 34, 672, 254] },
  { id: 4, range: [0, 260, 220, 480] },
  { id: 5, range: [226, 260, 446, 480] },
  { id: 6, range: [452, 260, 672, 480] },
];
const UA = process.env.SOLVER_UA ||
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";

const log = (...a) => console.log(`[${new Date().toISOString()}]`, ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let msgId = 0;
const DEBUG_HOST = `http://127.0.0.1:${DEBUG_PORT}`;

function httpJson(p, method = "GET") {
  return new Promise((resolve, reject) => {
    const req = http.request(DEBUG_HOST + p, { method, timeout: 5000 }, (res) => {
      let d = "";
      res.on("data", (c) => (d += c));
      res.on("end", () => { try { resolve(JSON.parse(d)); } catch { resolve(d); } });
    });
    req.on("timeout", () => req.destroy(new Error("timeout")));
    req.on("error", reject);
    req.end();
  });
}

class Target {
  constructor(wsUrl, name) {
    this.name = name;
    this.ws = new WebSocket(wsUrl, { perMessageDeflate: false, maxPayload: 64 * 1024 * 1024 });
    this.pending = new Map();
    this.handlers = [];
    this.ready = false;
    this.closed = false;
    this.ws.on("open", () => { this.ready = true; });
    this.ws.on("close", () => { this.closed = true; });
    this.ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      } else if (msg.method) this.handlers.forEach((h) => h(msg));
    });
    this.ws.on("error", () => {});
  }
  send(method, params = {}, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      if (this.closed) return reject(new Error(`target ${this.name} closed`));
      const id = ++msgId;
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) {
          reject(new Error(`target ${this.name} cmd timeout: ${method}`));
        }
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  onMessage(h) { this.handlers.push(h); }
  async waitReady(t = 10000) {
    const t0 = Date.now();
    while (!this.ready && Date.now() - t0 < t) await sleep(100);
    if (!this.ready) throw new Error(`target ${this.name} not ready`);
  }
  close() { try { this.ws.close(); } catch {} }
}

// ---------------------------------------------------------------------------
// Chrome lifecycle
let chromeProc = null;
let solvesSinceLaunch = 0;

async function chromeAlive() {
  try {
    await httpJson("/json/version");
    return true;
  } catch {
    return false;
  }
}

async function ensureChrome() {
  if (await chromeAlive()) return;
  if (chromeProc) { try { chromeProc.kill("SIGKILL"); } catch {} chromeProc = null; }
  fs.rmSync(PROFILE_DIR, { recursive: true, force: true });
  const args = [
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${PROFILE_DIR}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-blink-features=AutomationControlled",
    "--disable-features=Translate,OptimizationHints,CalculateNativeWinOcclusion",
    "--disable-background-timer-throttling",
    "--disable-backgrounding-occluded-windows",
    "--disable-renderer-backgrounding",
    "--window-size=1280,900",
    "--lang=zh-CN",
    "about:blank",
  ];
  if (HEADLESS) args.unshift("--headless=new");
  if (process.env.CHROME_EXTRA_ARGS) args.unshift(...process.env.CHROME_EXTRA_ARGS.split(" ").filter(Boolean));
  chromeProc = spawn(CHROME, args, { stdio: ["ignore", "ignore", "ignore"], detached: false });
  chromeProc.on("exit", () => { chromeProc = null; });
  const t0 = Date.now();
  while (Date.now() - t0 < 20000) {
    if (await chromeAlive()) break;
    await sleep(300);
  }
  if (!(await chromeAlive())) throw new Error("chrome failed to start");
  solvesSinceLaunch = 0;
  log("chrome ready");
}

// ---------------------------------------------------------------------------
// Solve
function findIframeTarget(timeoutMs) {
  const t0 = Date.now();
  return (async () => {
    while (Date.now() - t0 < timeoutMs) {
      const targets = await httpJson("/json").catch(() => []);
      const t = (targets || []).find(
        (x) => x.type === "iframe" && /captcha\.gtimg\.com\/static\/template/.test(x.url || "")
      );
      if (t) return t;
      await sleep(150);
    }
    return null;
  })();
}

function parseJsonp(body) {
  const i = body.indexOf("(");
  const j = body.lastIndexOf(")");
  if (i < 0 || j <= i) throw new Error("bad jsonp");
  return JSON.parse(body.slice(i + 1, j));
}

async function pickRegion(imageB64, instruction, regions) {
  const payload = JSON.stringify({ image_b64: imageB64, instruction, regions });
  return new Promise((resolve, reject) => {
    const u = new URL(CLIP_URL);
    const req = http.request(
      { host: u.hostname, port: u.port, path: u.pathname, method: "POST", timeout: 30000,
        headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) } },
      (res) => {
        let d = "";
        res.on("data", (c) => (d += c));
        res.on("end", () => { try { resolve(JSON.parse(d)); } catch (e) { reject(new Error(`clip bad json: ${d.slice(0, 120)}`)); } });
      }
    );
    req.on("timeout", () => req.destroy(new Error("clip timeout")));
    req.on("error", reject);
    req.write(payload);
    req.end();
  });
}

// “依次点击”题型的 sprite/主图匹配（seq_match.py，多尺度模板匹配）。
async function seqPick(imageB64, spriteB64) {
  const payload = JSON.stringify({ image_b64: imageB64, sprite_b64: spriteB64 });
  const u = new URL(CLIP_URL);
  const path = u.pathname.replace(/\/pick$/, "/seq-pick");
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: u.hostname, port: u.port, path, method: "POST", timeout: 90000,
        headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) } },
      (res) => {
        let d = "";
        res.on("data", (c) => (d += c));
        res.on("end", () => { try { resolve(JSON.parse(d)); } catch (e) { reject(new Error(`seq-pick bad json: ${d.slice(0, 120)}`)); } });
      }
    );
    req.on("timeout", () => req.destroy(new Error("seq-pick timeout")));
    req.on("error", reject);
    req.write(payload);
    req.end();
  });
}

async function solveOnce(entryUrl, appid, retries) {
  const startedAt = Date.now();
  await ensureChrome();
  if (solvesSinceLaunch >= RECYCLE_SOLVES) {
    log("recycling chrome after", solvesSinceLaunch, "solves");
    try { chromeProc.kill("SIGKILL"); } catch {}
    chromeProc = null;
    await ensureChrome();
  }
  solvesSinceLaunch++;

  const tab = await httpJson(`/json/new?${encodeURIComponent("about:blank")}`, "PUT");
  const page = new Target(tab.webSocketDebuggerUrl, "page");
  await page.waitReady();
  // 并发求解时保持两个 tab 都不被 Chrome 后台节流。
  await page.send("Page.bringToFront").catch(() => {});

  // state captured from the wire
  let challenge = null;         // {instruction, regions, bgW, bgH}
  let challengeTs = 0;
  let imageB64 = null;
  let imageTs = 0;
  let spriteB64 = null;
  let spriteTs = 0;
  let lastVerify = null;
  const verifyResponses = [];   // chronological verify bodies
  let completionSeen = false;
  let verifyReqCount = 0;
  let pageCompletionTicket = null; // auto-pass path: ticket arrives via the page's /complete POST
  let forgedCompleteCount = 0;

  const CAP_RE = /(t\.captcha\.qq\.com|turing\.captcha|captcha\.gtimg|captcha\.qq\.com)/;
  const pendingBodies = new Map(); // requestId -> { url, tgt }
  const imageStore = new Map(); // imageKey -> { b64, ts }

  const getBodyWithRetry = async (tgt, rid) => {
    for (let i = 0; i < 6; i++) {
      try {
        const r = await tgt.send("Network.getResponseBody", { requestId: rid });
        if (r.body) {
          return r.base64Encoded ? Buffer.from(r.body, "base64") : Buffer.from(r.body, "utf8");
        }
      } catch {}
      await sleep(180);
    }
    return null;
  };

  const processBody = (url, raw) => {
    if (!raw || !raw.length) return;
    const asText = () => raw.toString("utf8");
    if (/cap_union_prehandle/.test(url) || /cap_union_new_getsig/.test(url)) {
      try {
        let data;
        try { data = parseJsonp(asText()); } catch { data = JSON.parse(asText()); }
        if (data && data.data && data.data.dyn_show_info && data.data.dyn_show_info.instruction) {
          const dyn = data.data.dyn_show_info;
          let regions = null;
          try {
            const jp = JSON.parse(dyn.json_payload || "{}");
            regions = (jp.select_region_list || []).map((x) => ({ id: x.id, range: x.range }));
          } catch {}
          if (regions && regions.length) {
            let imageKey = null;
            try {
              const mk = String(dyn.bg_elem_cfg?.img_url || "").match(/image=([^&]+)/);
              imageKey = mk ? mk[1] : null;
            } catch {}
            challenge = {
              instruction: String(dyn.instruction || "").replace(/[\u201c\u201d"']/g, "").trim(),
              regions,
              bgW: (dyn.bg_elem_cfg?.size_2d || [672, 480])[0],
              bgH: (dyn.bg_elem_cfg?.size_2d || [672, 480])[1],
              imageKey,
            };
            challengeTs = Date.now();
            log("challenge:", JSON.stringify({ instruction: challenge.instruction, n: regions.length, src: /getsig/.test(url) ? "getsig" : "prehandle" }));
          }
        }
      } catch (e) { log("challenge parse failed:", String(e).slice(0, 120)); }
    } else if (/getcapbysig/.test(url)) {
      const mk = url.match(/image=([^&]+)/);
      const key = mk ? mk[1] : ("nokey-" + Date.now());
      const idxMatch = url.match(/img_index=(\d+)/);
      const imgIndex = idxMatch ? parseInt(idxMatch[1], 10) : 1;
      if (imgIndex === 0) {
        spriteB64 = raw.toString("base64");
        spriteTs = Date.now();
        log("sprite captured:", raw.length, "bytes");
        return;
      }
      imageStore.set(key, { b64: raw.toString("base64"), ts: Date.now() });
      imageB64 = raw.toString("base64");
      imageTs = Date.now();
      log("image captured:", raw.length, "bytes key=", key.slice(0, 20));
    } else if (/cap_union_new_verify/.test(url)) {
      try {
        lastVerify = JSON.parse(asText());
        verifyResponses.push(lastVerify);
        log("verify resp:", JSON.stringify({ errorCode: lastVerify.errorCode, tip: lastVerify.extra?.tip || "", ticket: (lastVerify.ticket || "").slice(0, 12) }));
      } catch {}
    }
  };

  const captureHandler = (tgt) => async (msg) => {
    if (msg.method === "Network.requestWillBeSent") {
      const ru = msg.params?.request?.url || "";
      if (/cap_union_new_verify/.test(ru)) { verifyReqCount++; log(`verify REQ fired (${tgt.name}) post=${(msg.params.request.postData || "").length}B`); }
      return;
    }
    if (msg.method === "Network.responseReceived") {
      const u = msg.params?.response?.url || "";
      if (!CAP_RE.test(u)) return;
      pendingBodies.set(`${tgt.name}:${msg.params.requestId}`, { url: u, tgt, rid: msg.params.requestId });
      return;
    }
    if (msg.method === "Network.loadingFinished") {
      const key = `${tgt.name}:${msg.params.requestId}`;
      const rec = pendingBodies.get(key);
      if (!rec) return;
      pendingBodies.delete(key);
      const raw = await getBodyWithRetry(rec.tgt, rec.rid);
      if (raw) processBody(rec.url, raw);
      return;
    }
    if (msg.method === "Network.loadingFailed") {
      pendingBodies.delete(`${tgt.name}:${msg.params.requestId}`);
    }
  };
  page.onMessage(captureHandler(page));
  let childIframeTargetId = null;
  page.onMessage((msg) => {
    if (msg.method === "Target.attachedToTarget") {
      const ti = msg.params?.targetInfo || {};
      if (ti.type === "iframe" && /captcha\.gtimg\.com\/static\/template/.test(ti.url || "")) {
        childIframeTargetId = ti.targetId;
      }
    }
  });
  await page
    .send("Target.setAutoAttach", { autoAttach: true, waitForDebuggerOnStart: false, flatten: true })
    .catch((e) => log("autoAttach unavailable:", String(e).slice(0, 100)));

  try {
    await page.send("Network.enable");
    await page.send("Page.enable");
    await page.send("Runtime.enable");
    // Block the page's own completion POST (we keep single-completer semantics).
    try {
      const blockPattern = { urlPattern: "*weread/eink/verify*complete*", requestStage: "Request" };
      await page.send("Fetch.enable", { patterns: [blockPattern] });
      page.onMessage(async (msg) => {
        if (msg.method !== "Fetch.requestPaused") return;
        completionSeen = true;
        try {
          const j = JSON.parse(msg.params.request.postData || "{}");
          const t = String(j.ticket || "");
          if (t) {
            if (t.startsWith("terror_")) {
              forgedCompleteCount++;
              log("forged fallback ticket from widget:", t.slice(0, 24));
            } else {
              pageCompletionTicket = { ticket: t, randstr: String(j.randstr || "") };
              log("captured ticket from blocked /complete:", t.slice(0, 12));
            }
          }
        } catch {}
        log("blocked page /complete");
        await page.send("Fetch.failRequest", { requestId: msg.params.requestId, errorReason: "Aborted" }).catch(() => {});
      });
    } catch (e) { log("fetch domain unavailable", String(e).slice(0, 80)); }

    await sleep(300);
    let nav = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      nav = await page.send("Page.navigate", { url: entryUrl, transitionType: "typed" }).catch((e) => ({ errorText: String(e) }));
      if (nav && !nav.errorText) break;
      log(`nav attempt ${attempt + 1} failed: ${nav?.errorText}`);
      await sleep(2000);
    }
    if (!nav || nav.errorText) throw new Error(`nav failed: ${nav.errorText}`);
    // wait TencentCaptcha loaded
    for (let i = 0; i < 60; i++) {
      const r = await page.send("Runtime.evaluate", { expression: "typeof TencentCaptcha === 'function'", returnByValue: true }).catch(() => null);
      if (r?.result?.value === true) break;
      await sleep(400);
    }
    await page.send("Runtime.evaluate", { expression: "document.getElementById('verify') && document.getElementById('verify').click()" }).catch(() => {});
    log("clicked #verify");

    let iframe = null;
    {
      const t0 = Date.now();
      let reclicked = false;
      while (Date.now() - t0 < 30000) {
        if (pageCompletionTicket) break;
        if (forgedCompleteCount > 0 && !reclicked) {
          reclicked = true;
          log("retrying captcha after forged fallback ticket");
          await page.send("Runtime.evaluate", { expression: "document.getElementById('verify') && document.getElementById('verify').click()" }).catch(() => {});
        }
        if (childIframeTargetId) {
          const all = await httpJson("/json").catch(() => []);
          iframe = (all || []).find((x) => x.id === childIframeTargetId) || null;
          if (iframe) { log("iframe bound via auto-attach"); break; }
          childIframeTargetId = null;
        }
        iframe = await findIframeTarget(500);
        if (iframe) break;
      }
    }
    if (!iframe && !pageCompletionTicket) {
      // 验证码组件偶发未加载：整页重载一次再等，不要直接判失败。
      log("captcha iframe not found after 30s -> reload and retry once");
      await page.send("Page.reload", { ignoreCache: true }).catch(() => {});
      await sleep(2500);
      for (let i = 0; i < 50; i++) {
        const r = await page.send("Runtime.evaluate", { expression: "typeof TencentCaptcha === 'function'", returnByValue: true }).catch(() => null);
        if (r?.result?.value === true) break;
        await sleep(400);
      }
      await page.send("Runtime.evaluate", { expression: "document.getElementById('verify') && document.getElementById('verify').click()" }).catch(() => {});
      const t0 = Date.now();
      while (Date.now() - t0 < 20000) {
        if (pageCompletionTicket) break;
        iframe = await findIframeTarget(500);
        if (iframe) break;
      }
      if (iframe) log("iframe found after page reload");
    }
    if (pageCompletionTicket) {
      return { ok: true, attempts: 0, ticket: pageCompletionTicket.ticket, randstr: pageCompletionTicket.randstr, costS: (Date.now() - startedAt) / 1000 };
    }
    if (!iframe) {
      if (process.env.DEBUG_SAVE === "1") {
        try {
          const stamp = new Date().toISOString().replace(/[:.]/g, "-");
          const shot = await page.send("Page.captureScreenshot", { format: "jpeg", quality: 60 }).catch(() => null);
          if (shot?.data) fs.writeFileSync(path.join(DEBUG_DIR, `iframe-missing-${stamp}.jpg`), Buffer.from(shot.data, "base64"));
          const html = await page.send("Runtime.evaluate", { expression: "document.documentElement.outerHTML.slice(0, 200000)", returnByValue: true }).catch(() => null);
          if (html?.result?.value) fs.writeFileSync(path.join(DEBUG_DIR, `iframe-missing-${stamp}.html`), String(html.result.value));
        } catch {}
      }
      throw new Error("captcha iframe not found");
    }
    let ifr = new Target(iframe.webSocketDebuggerUrl, "iframe");
    await ifr.waitReady();
    await ifr.send("Network.enable").catch(() => {});
    await ifr.send("Runtime.enable").catch(() => {});
    ifr.onMessage(captureHandler(ifr));

    const LIVE_SYNC = "(() => { const t = document.getElementById('instructionText'); const bg = document.getElementById('slideBg'); const instr = t ? (t.textContent || '') : ''; let url = null; if (bg) { const cs = getComputedStyle(bg).backgroundImage || ''; const m = cs.match(/url\\([\"']?([^\"')]+)[\"']?\\)/); url = m ? m[1] : null; if (!url) { const im = bg.querySelector('img'); url = im ? im.src : null; } } const btns = [...document.querySelectorAll('.verifyButton, #verifyBtn, .tc-verify-button')]; const vis = btns.find(b => b.offsetWidth > 10 && b.offsetHeight > 5); return JSON.stringify({ instr, url, btnVisible: !!vis }); })()";
    let liveErrorLogged = false;
    const readLive = async () => {
      try {
        const r = await ifr.send("Runtime.evaluate", { expression: LIVE_SYNC, returnByValue: true });
        if (r && r.exceptionDetails) {
          if (!liveErrorLogged) { liveErrorLogged = true; log("live eval exc:", JSON.stringify(r.exceptionDetails).slice(0, 200)); }
          return null;
        }
        return JSON.parse(r.result.value || "{}");
      } catch (e) {
        if (!liveErrorLogged) { liveErrorLogged = true; log("live eval err:", String(e).slice(0, 160)); }
        return null;
      }
    };
    const fetchB64 = async (url) => {
      try {
        const expr = "(async () => { try { const blob = await (await fetch(" + JSON.stringify(url) + ", { cache: 'no-store' })).blob(); return await new Promise((res, rej) => { const fr = new FileReader(); fr.onload = () => res(String(fr.result).split(',')[1]); fr.onerror = rej; fr.readAsDataURL(blob); }); } catch (e) { return null; } })()";
        const r = await ifr.send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true });
        if (r && typeof r.result.value === "string" && r.result.value.length > 5000) return r.result.value;
        return null;
      } catch { return null; }
    };
    let lastSubmittedInstr = null;
    let lastSubmittedUrl = null;

    const maxRounds = Math.max(2, MAX_ROUNDS);
    const runStamp = new Date().toISOString().replace(/[:.]/g, "-");
    let rounds = 0;
    let seqRounds = 0;
    let textSkips = 0;
    let lastHandledTs = 0;
    let lastImageTs = 0;
    let lastClicked = [];
    let needFresh = true; // errorCode 50 = re-answer same question, no new challenge

    let cursor = { x: 640 + Math.random() * 40, y: 450 + Math.random() * 40 };
    const moveTo = async (x, y) => {
      // human-like path: quadratic bezier with jitter, ~30ms per step
      const cx = (cursor.x + x) / 2 + (Math.random() - 0.5) * 120;
      const cy = (cursor.y + y) / 2 + (Math.random() - 0.5) * 120;
      const steps = 14 + Math.floor(Math.random() * 10);
      for (let i = 1; i <= steps; i++) {
        const t = i / steps;
        const px = (1 - t) * (1 - t) * cursor.x + 2 * (1 - t) * t * cx + t * t * x + (Math.random() - 0.5) * 3;
        const py = (1 - t) * (1 - t) * cursor.y + 2 * (1 - t) * t * cy + t * t * y + (Math.random() - 0.5) * 3;
        await ifr.send("Input.dispatchMouseEvent", { type: "mouseMoved", x: Math.round(px), y: Math.round(py) });
        await sleep(24 + Math.random() * 18);
      }
      cursor = { x, y };
    };
    const clickAt = async (x, y) => {
      if (SLOW_PACE) await moveTo(x, y);
      await sleep(90 + Math.random() * 160);
      for (const type of ["mousePressed", "mouseReleased"]) {
        await ifr.send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 });
        await sleep(60 + Math.random() * 60);
      }
      cursor = { x, y };
    };
    const rectOf = async (expr) => {
      const r = await ifr.send("Runtime.evaluate", { expression: expr, returnByValue: true });
      return JSON.parse(r.result.value);
    };

    const deadline = startedAt + SOLVE_BUDGET_MS;
    // 单轮内任何一段慢操作后都检查预算；预算不足直接抛，保证在 App 超时前返回。
    const ensureBudget = (needMs, stage) => {
      if (deadline - Date.now() < needMs) {
        throw new Error(`budget exhausted before ${stage} (need ${needMs}ms)`);
      }
    };
    while (Date.now() < deadline && rounds < maxRounds) {
      // 剩余预算不足一轮完整提交时，体面收尾而不是用过期 deadline 制造误报超时。
      if (deadline - Date.now() < 22000) {
        log("budget nearly exhausted, stopping with", rounds, "rounds");
        break;
      }
      // wait for a stable, not-yet-submitted challenge read from the live DOM
      const waitDeadline = Math.min(deadline, Date.now() + 28000);
      let live = null;
      let stableCount = 0;
      let lastSeen = null;
      while (Date.now() < waitDeadline && !pageCompletionTicket) {
        const v = await readLive();
        if (v && v.instr) {
          const key = v.instr + "|" + (v.url || "");
          if (key === lastSeen) stableCount++; else { lastSeen = key; stableCount = 0; }
          const fresh = v.instr !== lastSubmittedInstr || (v.url && v.url !== lastSubmittedUrl);
          const m0 = String(v.url || "").match(/image=([^&]+)/);
          const netReady = m0 && imageStore.has(m0[1]) && imageStore.get(m0[1]).b64 && imageStore.get(m0[1]).b64.length > 5000;
          const imgReady = netReady || (imageB64 && imageB64.length > 5000) || !!v.url;
          const badInstr = /难|更换|作答|网络|失败|超时|稍后/.test(v.instr);
          if (stableCount >= 2 && fresh && !badInstr && v.btnVisible === true && !!v.instr && imgReady) { live = v; break; }
          if (stableCount === 1) log("live candidate:", JSON.stringify(v).slice(0, 160), "netReady=", netReady);
        } else {
          stableCount = 0;
        }
        await sleep(500);
      }
      if (pageCompletionTicket) {
        return { ok: true, attempts: rounds, ticket: pageCompletionTicket.ticket, randstr: pageCompletionTicket.randstr, costS: (Date.now() - startedAt) / 1000 };
      }
      if (!live) {
        log("live challenge timeout");
        break;
      }
      rounds++;
      await think(2200, 4200); // "looking at the picture" like a human
      const keyMatch = String(live.url || "").match(/image=([^&]+)/);
      const netKey = keyMatch ? keyMatch[1] : null;
      const stored = netKey && imageStore.has(netKey) ? imageStore.get(netKey) : null;
      let domB64 = null;
      if (!(stored && stored.b64)) {
        domB64 = await fetchB64(live.url);
        if (!domB64) domB64 = await fetchB64(live.url);
      }
      const pickImageB64 = domB64 || (stored ? stored.b64 : null) || (imageB64 && imageB64.length > 5000 ? imageB64 : null);
      if (!pickImageB64) { log("no image available for round", rounds); break; }
      const instr = String(live.instr).replace(/[\u201c\u201d"']/g, "").trim();
      log(`round ${rounds}: live instr=${JSON.stringify(instr)} src=${domB64 ? "dom" : (stored ? "net-key" : "net-latest")} b64len=${pickImageB64 ? pickImageB64.length : 0} key=${(netKey || "").slice(0, 20)}`);
      // 升级题型“依次点击：”（icon-float）：提示图标在题面 DOM 里，答案要按序点击到图上。
      const seqMode = /依次点击/.test(instr);
      if (seqMode) {
        try {
          const dump = await ifr.send("Runtime.evaluate", {
            expression: `(() => {
              const t = document.getElementById('instructionText');
              const wrap = t ? (t.closest('.tc-header, .tc-wrap, div') || t.parentElement) : document.body;
              const imgs = [...document.querySelectorAll('img')].map(i => ({ src: (i.src || '').slice(0, 160), w: i.offsetWidth, h: i.offsetHeight, x: Math.round(i.getBoundingClientRect().x), y: Math.round(i.getBoundingClientRect().y) })).slice(0, 20);
              const spans = [...document.querySelectorAll('#instructionText *, .tc-header *')].slice(0, 40).map(e => ({ tag: e.tagName, cls: String(e.className || '').slice(0, 40), bg: String(getComputedStyle(e).backgroundImage || '').slice(0, 120), w: e.offsetWidth, h: e.offsetHeight }));
              return JSON.stringify({ html: wrap.outerHTML.slice(0, 7000), imgs, spans: spans.slice(0, 25) });
            })()`,
            returnByValue: true,
          });
          fs.mkdirSync(DEBUG_DIR, { recursive: true });
          fs.writeFileSync(path.join(DEBUG_DIR, "seq-dom.json"), String(dump?.result?.value || "{}"));
          fs.writeFileSync(path.join(DEBUG_DIR, `seq-r${rounds}-${runStamp}-img.jpg`), Buffer.from(pickImageB64, "base64"));
          if (spriteB64) fs.writeFileSync(path.join(DEBUG_DIR, `seq-r${rounds}-${runStamp}-sprite.png`), Buffer.from(spriteB64, "base64"));
          log("seq-mode DOM dumped (依次点击)");
        } catch (e) { log("seq dom dump failed:", String(e).slice(0, 100)); }
      }
      let seqSubmitted = false;
      if (seqMode) {
        seqRounds++;
        // 升级题型“依次点击：”：sprite 剪影序列做多尺度模板匹配，命中后按序点击。
        ensureBudget(20000, "seq-pick");
        let sprite = spriteB64;
        const spriteStale = !sprite || (Date.now() - spriteTs > 8000);
        if (spriteStale && live.url) {
          const spriteUrl = String(live.url).replace(/img_index=1/, "img_index=0");
          if (spriteUrl !== String(live.url)) {
            const fresh = await fetchB64(spriteUrl);
            if (fresh) sprite = fresh;
          }
        }
        let seq = null;
        if (sprite) {
          try {
            seq = await seqPick(pickImageB64, sprite);
          } catch (e) { log("seq-pick error:", String(e).slice(0, 120)); }
        }
        const confident = seq && seq.ok && Array.isArray(seq.clicks) && seq.clicks.length > 0
          && typeof seq.min_score === "number" && seq.min_score >= SEQ_MIN_SCORE;
        if (confident && seqRounds <= SEQ_MAX_ROUNDS) {
          const sx = 672 / (seq.img_w || 672);
          const sy = 480 / (seq.img_h || 480);
          const pts = seq.clicks.map((c) => ({ x: c.x * sx, y: c.y * sy, score: c.score }));
          log(`seq pick ok: n=${pts.length} min=${seq.min_score} clicks=${JSON.stringify(pts.map((p) => [Math.round(p.x), Math.round(p.y)]))}`);
          if (process.env.DEBUG_SAVE === "1") {
            try {
              fs.mkdirSync(DEBUG_DIR, { recursive: true });
              const stamp = new Date().toISOString().replace(/[:.]/g, "-");
              fs.writeFileSync(path.join(DEBUG_DIR, `seq-r${rounds}-${runStamp}-pick.json`), JSON.stringify({ runStamp, seq, points: pts, liveUrl: live.url || null }));
              fs.appendFileSync(
                path.join(DEBUG_DIR, "seq-attempts.jsonl"),
                JSON.stringify({ ts: new Date().toISOString(), rounds, min: seq.min_score, scores: seq.scores, points: pts }) + "\n",
              );
            } catch {}
          }
          const bgR = await rectOf("(() => { const bg = document.getElementById('slideBg'); if (!bg) return 'null'; const r = bg.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height}); })()");
          if (!bgR || bgR.w === undefined) throw new Error("slideBg not found");
          for (const p of pts) {
            const cx = bgR.x + p.x * (bgR.w / 672);
            const cy = bgR.y + p.y * (bgR.h / 480);
            await clickAt(Math.round(cx), Math.round(cy));
            await sleep(120 + Math.random() * 140);
          }
          lastSubmittedInstr = instr;
          lastSubmittedUrl = live.url || null;
          lastClicked = pts.map((p) => [Math.round(p.x), Math.round(p.y)]);
          seqSubmitted = true;
        } else {
          if (seqRounds > SEQ_MAX_ROUNDS) {
            throw new Error(`seq tasks unresolved after ${seqRounds} rounds (min=${seq && typeof seq.min_score === "number" ? seq.min_score : "n/a"})`);
          }
          log("seq pick low confidence -> refresh", seq ? `min=${seq.min_score}` : "(no result)");
          try {
            const rr = await rectOf("(() => { const b = document.querySelector('.tc-refresh-icon') || document.getElementById('ticon_refresh'); if (!b) return 'null'; const r = b.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height}); })()");
            if (rr && rr.x !== undefined) await clickAt(Math.round(rr.x + rr.w / 2), Math.round(rr.y + rr.h / 2));
          } catch {}
          lastSubmittedInstr = instr;
          lastSubmittedUrl = live.url || null;
          await sleep(1200);
          continue;
        }
      }
      if (!seqSubmitted) {
        ensureBudget(20000, "pick");
        const pick = await pickRegion(pickImageB64, instr, CONST_GRID);
        if (!pick?.ok) throw new Error(`pick failed: ${pick?.error || "unknown"}`);
        log(`round ${rounds}: pick=${pick.region_id} set=${JSON.stringify(pick.region_ids || [pick.region_id])} method=${pick.method}`);
        lastSubmittedInstr = instr;
        lastSubmittedUrl = live.url || null;
        if (process.env.DEBUG_SAVE === "1") {
          try {
            fs.mkdirSync(DEBUG_DIR, { recursive: true });
            fs.writeFileSync(path.join(DEBUG_DIR, `r${rounds}-${runStamp}-img.jpg`), Buffer.from(pickImageB64, "base64"));
            fs.writeFileSync(path.join(DEBUG_DIR, `r${rounds}-${runStamp}-pick.json`), JSON.stringify({ runStamp, instruction: instr, pick, regions: CONST_GRID, liveUrl: live.url || null, src: live.b64 ? "dom" : "net" }));
          } catch {}
        }

        const bgRect = await rectOf(
          "(() => { const bg = document.getElementById('slideBg'); if (!bg) return 'null'; const r = bg.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height}); })()"
        );
        if (!bgRect || bgRect.w === undefined) throw new Error("slideBg not found");
        let ids = (Array.isArray(pick.region_ids) && pick.region_ids.length ? pick.region_ids : [pick.region_id]).filter((x) => typeof x === "number" && x >= 1 && x <= 6);
        const textTask = /包含文字/.test(instr);
        if (textTask && (pick.low_confidence === true || !ids.length)) {
          textSkips++;
          if (textSkips > TEXT_MAX_SKIPS) {
            throw new Error(`text tasks unresolved (skips=${textSkips}, method=${pick.method})`);
          }
          log(`text pick low confidence (method=${pick.method}, conf=${pick.confidence}) -> refresh ${textSkips}/${TEXT_MAX_SKIPS}`);
          try {
            const rr = await rectOf("(() => { const b = document.querySelector('.tc-refresh-icon') || document.getElementById('ticon_refresh'); if (!b) return 'null'; const r = b.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height}); })()");
            if (rr && rr.x !== undefined) await clickAt(Math.round(rr.x + rr.w / 2), Math.round(rr.y + rr.h / 2));
          } catch {}
          lastSubmittedInstr = instr;
          lastSubmittedUrl = live.url || null;
          await sleep(1200);
          continue;
        }
        if (!ids.length) {
          log("round", rounds, ": empty pick set -> clicking refresh and continuing");
          try {
            const rr = await rectOf("(() => { const b = document.querySelector('.tc-refresh-icon') || document.getElementById('ticon_refresh'); if (!b) return 'null'; const r = b.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height}); })()");
            if (rr && rr.x !== undefined) await clickAt(Math.round(rr.x + rr.w / 2), Math.round(rr.y + rr.h / 2));
          } catch {}
          await sleep(1500);
          continue;
        }
        log(`click set = ${JSON.stringify(ids)} (bgRect=${JSON.stringify(bgRect)})`);
        lastClicked = ids.slice();
        for (const rid of ids) {
          const reg = CONST_GRID.find((r) => r.id === rid);
          if (!reg) continue;
          const [x1, y1, x2, y2] = reg.range;
          const cx = bgRect.x + ((x1 + x2) / 2) * (bgRect.w / 672);
          const cy = bgRect.y + ((y1 + y2) / 2) * (bgRect.h / 480);
          await clickAt(Math.round(cx), Math.round(cy));
          await sleep(90 + Math.random() * 90);
        }
      }
      await sleep(500 + Math.random() * 400);
      try {
        const maskInfo = await ifr.send("Runtime.evaluate", {
          expression: "JSON.stringify({masks: [...document.querySelectorAll('*')].filter(e => /mask/i.test(typeof e.className === 'string' ? e.className : '')).length, btn: (() => { const b = document.querySelector('.verifyButton') || document.getElementById('verifyBtn'); if (!b) return null; const r = b.getBoundingClientRect(); return {x:r.x,y:r.y,w:r.width,h:r.height,dis:b.disabled===true, pe:getComputedStyle(b).pointerEvents, op:getComputedStyle(b).opacity}; })()})",
          returnByValue: true,
        });
        log("after tile click:", maskInfo?.result?.value);
      } catch (e) { log("mask diag failed", String(e).slice(0, 100)); }

      // confirm button: click the first visible candidate (.verifyButton / #verifyBtn)
      ensureBudget(15000, "confirm");
      await sleep(400);
      const reqsBefore = verifyReqCount;
      const countBefore = verifyResponses.length;
      try {
        const btn = await rectOf(
          "(() => { const els = [...document.querySelectorAll('.verifyButton, #verifyBtn, .tc-verify-button')]; const vis = els.filter(b => { const r = b.getBoundingClientRect(); return r.width > 10 && r.height > 5; }); if (!vis.length) return JSON.stringify({candidates: els.length, visible: 0}); const b = vis[0]; const r = b.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height, candidates: els.length, cls: (typeof b.className==='string'?b.className:'')}); })()"
        );
        log("confirm candidates:", JSON.stringify(btn));
        if (btn && btn.x !== undefined && btn.w) {
          await clickAt(Math.round(btn.x + btn.w / 2), Math.round(btn.y + btn.h / 2));
          // retry the confirm click if the verify request did not fire
        }
      } catch (e) { log("confirm click failed", String(e).slice(0, 100)); }

      // wait for the verify outcome of this round (char tasks can take 10-15s
      // to build the payload before the request fires; do not re-click)
      ensureBudget(12000, "verify-wait");
      const vDeadline = Math.min(deadline, Date.now() + 28000);
      while (Date.now() < vDeadline && verifyResponses.length === countBefore) await sleep(400);
      const resp = verifyResponses[verifyResponses.length - 1];
      if (!resp || verifyResponses.length === countBefore) {
        log("verify response timeout");
        throw new Error("verify response timeout");
      }
      const code = String(resp.errorCode);
      if (process.env.DEBUG_SAVE === "1") {
        try {
          fs.appendFileSync(
            path.join(DEBUG_DIR, "verify-outcomes.jsonl"),
            JSON.stringify({
              ts: new Date().toISOString(), runStamp, round: rounds,
              instr: lastSubmittedInstr, clicked: lastClicked, errorCode: code,
            }) + "\n",
          );
        } catch {}
      }
      if (code === "0") {
        const ticket = String(resp.ticket || "");
        const randstr = String(resp.randstr || "");
        if (!ticket || ticket.startsWith("terror_")) {
          throw new Error(`forged/empty ticket (${ticket.slice(0, 16)})`);
        }
        return { ok: true, attempts: rounds, ticket, randstr, costS: (Date.now() - startedAt) / 1000 };
      }
      if (code === "217") {
        throw new Error("rate limited (217)");
      }
      if (code === "20" || code === "206") {
        throw new Error(`session timeout (${code})`);
      }
      // 9/50/12/52 -> rotated or re-answer; loop for the next challenge
      if (code === "12" || code === "52") {
        log(`session error ${code} -> reloading page for a fresh session`);
        try {
          await page.send("Page.reload", {}).catch(() => {});
          await sleep(3500);
          for (let i = 0; i < 40; i++) {
            const r = await page.send("Runtime.evaluate", { expression: "typeof TencentCaptcha === 'function'", returnByValue: true }).catch(() => null);
            if (r?.result?.value === true) break;
            await sleep(400);
          }
          await page.send("Runtime.evaluate", { expression: "document.getElementById('verify') && document.getElementById('verify').click()" }).catch(() => {});
          const iframe2 = await findIframeTarget(20000);
          if (iframe2) {
            ifr = new Target(iframe2.webSocketDebuggerUrl, "iframe");
            await ifr.waitReady();
            await ifr.send("Network.enable").catch(() => {});
            await ifr.send("Runtime.enable").catch(() => {});
            ifr.onMessage(captureHandler(ifr));
            log("page reloaded, iframe re-attached");
          }
        } catch (e) { log("reload failed:", String(e).slice(0, 120)); }
        lastSubmittedInstr = null;
        lastSubmittedUrl = null;
        await sleep(1200);
        continue;
      }
      if (code === "50") needFresh = false; // same question, answer again in place
      log(`round ${rounds}: errorCode=${code} -> continue (needFresh=${needFresh})`);
      await sleep(300 + Math.random() * 300);
    }
    throw new Error(`no ticket after ${rounds} rounds`);
  } finally {
    try {
      await httpJson(`/json/close/${tab.id}`);
    } catch {}
    page.close();
  }
}

// ---------------------------------------------------------------------------
// HTTP server (bounded-concurrency queue; two accounts flap simultaneously)
const MAX_CONCURRENT = parseInt(process.env.SOLVER_MAX_CONCURRENT || "2", 10);
let activeCount = 0;
const queue = [];

function enqueueSolve(payload) {
  return new Promise((resolve) => {
    queue.push({ payload, resolve });
    pump();
  });
}

async function pump() {
  while (activeCount < MAX_CONCURRENT) {
    const job = queue.shift();
    if (!job) return;
    activeCount++;
    runJob(job).finally(() => {
      activeCount--;
      setImmediate(pump);
    });
  }
}

async function runJob(job) {
  try {
    const result = await solveOnce(job.payload.entry_url, job.payload.appid, job.payload.retries);
    job.resolve(result);
  } catch (e) {
    job.resolve({ ok: false, attempts: null, error: String(e.message || e).slice(0, 300) });
  }
}

const server = http.createServer((req, res) => {
  const send = (code, obj) => {
    const body = Buffer.from(JSON.stringify(obj));
    res.writeHead(code, { "Content-Type": "application/json; charset=utf-8", "Content-Length": body.length });
    res.end(body);
  };
  if (req.method === "GET" && req.url === "/health") {
    send(200, { status: "ok", browser: chromeProc !== null, active: activeCount, queued: queue.length, solves: solvesSinceLaunch });
    return;
  }
  if (req.method === "POST" && req.url === "/solve") {
    if (SK && (req.headers["x-sk"] || "").trim() !== SK) {
      send(401, { ok: false, error: "unauthorized" });
      return;
    }
    let raw = "";
    req.on("data", (c) => {
      raw += c;
      if (raw.length > 1024 * 1024) req.destroy();
    });
    req.on("end", async () => {
      let payload;
      try {
        payload = JSON.parse(raw || "{}");
      } catch {
        send(400, { ok: false, error: "bad json" });
        return;
      }
      if (!payload.entry_url || !/^https?:\/\//.test(payload.entry_url)) {
        send(400, { ok: false, error: "entry_url required" });
        return;
      }
      if (queue.length >= 3) {
        send(503, { ok: false, error: "overloaded" });
        return;
      }
      log("solve request:", JSON.stringify({ appid: payload.appid, retries: payload.retries, entry: String(payload.entry_url).slice(0, 90) }));
      const t0 = Date.now();
      // Heartbeat: the callers (OkHttp) may keep a 10s read timeout; send
      // whitespace chunks so the read window keeps resetting while we solve.
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Transfer-Encoding": "chunked" });
      const heartbeat = setInterval(() => {
        try { res.write(" "); } catch {}
      }, 4000);
      const result = await enqueueSolve(payload);
      clearInterval(heartbeat);
      result._cost_s = (Date.now() - t0) / 1000;
      log("solve result:", JSON.stringify({ ok: result.ok, attempts: result.attempts, err: result.error || "" }));
      res.write(JSON.stringify(result));
      res.end();
    });
    return;
  }
  send(404, { ok: false, error: "not found" });
});

(async () => {
  // Always start from a clean browser on boot to avoid reusing a wedged instance.
  try {
    execFileSync("pkill", ["-f", `remote-debugging-port=${DEBUG_PORT}`]);
  } catch {}
  await sleep(1200);
  try {
    await ensureChrome();
  } catch (e) {
    log("initial chrome start failed:", String(e));
  }
  server.listen(PORT, "127.0.0.1", () => log(`solver daemon listening on 127.0.0.1:${PORT}`));
})();
