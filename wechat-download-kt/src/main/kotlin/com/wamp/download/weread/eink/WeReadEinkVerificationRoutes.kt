package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun Route.weReadEinkVerificationRoutes() {
    get("/weread/eink/verify/{vid}/{nonce}") {
        val vid = call.parameters["vid"].orEmpty()
        val nonce = call.parameters["nonce"].orEmpty()
        if (!WeReadEinkHumanVerification.canOpen(vid, nonce)) {
            // 链接过期/已使用后不再返回纯文本死路：页面保留“获取新的验证链接”入口，
            // 与探测失败后的 nextUrl 体验一致，机器人里点开的旧链接也能一键换新。
            call.respondText(
                einkVerificationExpiredHtml(refreshPathForVid(vid)),
                status = HttpStatusCode.Gone,
                contentType = ContentType.parse("text/html; charset=UTF-8"),
            )
            return@get
        }
        applyVerificationPageHeaders(call, AppConfig.wereadEinkCaptchaScriptUrl)
        call.respondText(
            einkVerificationHtml(vid, nonce),
            contentType = ContentType.parse("text/html; charset=UTF-8"),
        )
    }

    // 兼容阶段三之前发出的旧链接（不含 vid）：按 nonce 反查账号后跳转到带 vid 的新链接。
    get("/weread/eink/verify/{nonce}") {
        val nonce = call.parameters["nonce"].orEmpty()
        val vid = WeReadEinkHumanVerification.findVidByNonce(nonce)
        if (vid != null) {
            call.respondRedirect("/weread/eink/verify/$vid/$nonce")
            return@get
        }
        call.respondText(
            einkVerificationExpiredHtml("/weread/eink/verify/refresh"),
            status = HttpStatusCode.Gone,
            contentType = ContentType.parse("text/html; charset=UTF-8"),
        )
    }

    post("/api/weread/eink/verify/{vid}/{nonce}/complete") {
        completeVerification(call, call.parameters["vid"].orEmpty(), call.parameters["nonce"].orEmpty())
    }

    // 兼容旧版不带 vid 的提交地址。
    post("/api/weread/eink/verify/{nonce}/complete") {
        val nonce = call.parameters["nonce"].orEmpty()
        val vid = WeReadEinkHumanVerification.findVidByNonce(nonce)
        if (vid == null) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("ok" to false, "code" to "NOT_FOUND", "message" to "验证链接不存在或已失效"),
            )
            return@post
        }
        completeVerification(call, vid, nonce)
    }

    // 兼容查询：主账号的验证状态。
    get("/api/weread/eink/verify/status") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.respond(
            WeReadEinkHumanVerification.statusForVidOrIdle(
                WeReadEinkRuntime.primaryVidOrNull(),
                AppConfig.siteUrl,
            ),
        )
    }

    get("/api/weread/eink/verify/{vid}/status") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.respond(WeReadEinkHumanVerification.status(call.parameters["vid"].orEmpty(), AppConfig.siteUrl))
    }

    get("/api/weread/eink/verify/evaluation") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.respond(WeReadEinkHumanVerification.evaluationForVidOrEmpty(WeReadEinkRuntime.primaryVidOrNull()))
    }

    get("/api/weread/eink/verify/{vid}/evaluation") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.respond(WeReadEinkHumanVerification.evaluation(call.parameters["vid"].orEmpty()))
    }

    get("/weread/eink/verify/{vid}/refresh") {
        respondVerificationRefresh(call, call.parameters["vid"].orEmpty())
    }

    // 兼容旧版不带 vid 的续期入口：定位到需要验证的账号（否则主账号）。
    get("/weread/eink/verify/refresh") {
        val vid = WeReadEinkRuntime.verificationTargetVid()
        if (vid == null) {
            call.respondText(
                einkVerificationNoticeHtml(
                    message = "当前没有需要人工验证的 E-Ink 账号。",
                    refreshPath = "/weread/eink/verify/refresh",
                ),
                status = HttpStatusCode.ServiceUnavailable,
                contentType = ContentType.parse("text/html; charset=UTF-8"),
            )
            return@get
        }
        respondVerificationRefresh(call, vid)
    }

    post("/api/weread/eink/verify/start") {
        val requested = call.request.queryParameters["vid"]?.takeIf { it.isNotBlank() }
        val vid = when {
            requested != null && WeReadEinkRuntime.hasAccount(requested) -> requested
            else -> WeReadEinkRuntime.verificationTargetVid()
        }
        if (vid == null || !WeReadEinkRuntime.requiresHumanVerification(vid)) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf(
                    "ok" to false,
                    "code" to "NOT_REQUIRED",
                    "message" to "当前 E-Ink 会话不需要人工验证",
                ),
            )
            return@post
        }
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.respond(
            mapOf(
                "ok" to true,
                "vid" to vid,
                "verification" to WeReadEinkHumanVerification.createOrReuse(vid, AppConfig.siteUrl),
            ),
        )
    }
}

private suspend fun completeVerification(call: ApplicationCall, vid: String, nonce: String) {
    val body = runCatching { call.receive<Map<String, String>>() }.getOrElse {
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "code" to "INVALID_BODY", "message" to "请求格式无效"),
        )
        return
    }
    val result = withContext(Dispatchers.IO) {
        WeReadEinkHumanVerification.complete(vid, nonce, body["ticket"], body["randstr"])
    }
    val status = when (result.code) {
        "RECOVERED" -> HttpStatusCode.OK
        "INVALID_CAPTCHA_RESULT" -> HttpStatusCode.BadRequest
        "NOT_FOUND" -> HttpStatusCode.NotFound
        "EXPIRED" -> HttpStatusCode.Gone
        "ALREADY_USED", "SESSION_CHANGED" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.UnprocessableEntity
    }
    call.respond(status, result)
}

private suspend fun respondVerificationRefresh(call: ApplicationCall, vid: String) {
    call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
    val refreshPath = refreshPathForVid(vid)
    if (vid.isBlank() || !WeReadEinkRuntime.hasAccount(vid)) {
        call.respondText(
            einkVerificationNoticeHtml(
                message = "该 E-Ink 账号不存在或已移除。",
                refreshPath = "/weread/eink/verify/refresh",
            ),
            status = HttpStatusCode.NotFound,
            contentType = ContentType.parse("text/html; charset=UTF-8"),
        )
        return
    }
    val current = WeReadEinkHumanVerification.status(vid, AppConfig.siteUrl)
    when (current.state) {
        WeReadEinkVerificationState.WAITING -> {
            // 已有未过期的等待链接：直接复用，避免不断生成新挑战。
            val url = current.verifyUrl
            if (!url.isNullOrBlank()) {
                call.respondRedirect(url)
                return
            }
        }
        WeReadEinkVerificationState.VERIFYING -> {
            call.respondText(
                einkVerificationNoticeHtml("上一次验证正在处理中，请稍候片刻再打开链接查看结果。", refreshPath),
                contentType = ContentType.parse("text/html; charset=UTF-8"),
            )
            return
        }
        else -> Unit
    }
    // IDLE / EXPIRED / FAILED / SUCCEEDED 均允许生成新链接：过期后一键续期。
    val verification = WeReadEinkHumanVerification.createOrReuse(vid, AppConfig.siteUrl)
    val verifyUrl = verification.verifyUrl
    if (verifyUrl.isNullOrBlank()) {
        call.respondText(
            einkVerificationNoticeHtml("暂时无法生成新的验证链接，请稍后再试。", refreshPath),
            status = HttpStatusCode.ServiceUnavailable,
            contentType = ContentType.parse("text/html; charset=UTF-8"),
        )
        return
    }
    call.respondRedirect(verifyUrl)
}

private fun applyVerificationPageHeaders(call: ApplicationCall, captchaScriptUrl: String) {
    call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
    call.response.header("Pragma", "no-cache")
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header("X-Content-Type-Options", "nosniff")
    val captchaOrigin = captchaScriptUrl.toHttpUrlOrNull()
        ?.let { "${it.scheme}://${it.host}:${it.port}" }
        .orEmpty()
    val scriptSrc = "script-src 'self' 'unsafe-inline' https: $captchaOrigin".trim()
    val connectSrc = "connect-src 'self' https: $captchaOrigin".trim()
    call.response.header(
        "Content-Security-Policy",
        "default-src 'self' https:; $scriptSrc; $connectSrc; " +
            "style-src 'self' 'unsafe-inline'; frame-src https:; img-src 'self' data: https:",
    )
}

internal fun refreshPathForVid(vid: String): String =
    if (vid.isBlank()) "/weread/eink/verify/refresh" else "/weread/eink/verify/${htmlEscape(vid)}/refresh"

internal fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/** 把值安全嵌入 <script> 内的 JS 字符串字面量，避免配置值或 vid/nonce 破坏页面脚本。 */
internal fun jsStringLiteral(value: String): String {
    val escaped = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
    }
    return "\"$escaped\""
}

internal fun einkVerificationHtml(
    vid: String,
    nonce: String,
    captchaScriptUrl: String = AppConfig.wereadEinkCaptchaScriptUrl,
    captchaAppId: String = AppConfig.wereadEinkCaptchaAppId,
): String = """
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1">
      <title>微信读书 E-Ink 人工验证</title>
      <style>
        body { margin: 0; background: #f5f7fa; color: #172033; font-family: system-ui, sans-serif; }
        main { max-width: 520px; margin: 10vh auto; padding: 32px; background: white; border-radius: 16px; box-shadow: 0 10px 35px #17203318; }
        button { width: 100%; padding: 14px; border: 0; border-radius: 10px; color: white; background: #07c160; font-size: 17px; cursor: pointer; }
        button:disabled { opacity: .55; cursor: wait; }
        #status { min-height: 24px; margin-top: 18px; line-height: 1.6; }
        .hint { color: #667085; line-height: 1.7; }
      </style>
      <script src="${htmlEscape(captchaScriptUrl)}"></script>
    </head>
    <body>
      <main>
        <h1>微信读书 E-Ink 人工验证</h1>
        <p class="hint">账号：${htmlEscape(vid)}<br>点击后完成腾讯验证码。成功结果会直接交给该账号的 E-Ink 会话，并立即执行恢复探测。</p>
        <button id="verify" type="button">开始验证</button>
        <div id="status" role="status"></div>
      </main>
      <script>
        const button = document.getElementById('verify');
        const statusBox = document.getElementById('status');
        const captchaAppId = ${jsStringLiteral(captchaAppId)};
        const completeUrl = ${jsStringLiteral("/api/weread/eink/verify/$vid/$nonce/complete")};
        const statusUrl = ${jsStringLiteral("/api/weread/eink/verify/$vid/status")};
        const verifyPagePath = ${jsStringLiteral("/weread/eink/verify/$vid/$nonce")};
        const refreshPath = ${jsStringLiteral(refreshPathForVid(vid))};
        let pollTimer = null;

        function stopPolling() {
          if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        }

        function showRenewLink(url) {
          const link = document.createElement('a');
          link.href = url;
          link.textContent = '打开新的验证链接';
          link.style.display = 'block';
          link.style.marginTop = '12px';
          statusBox.appendChild(link);
        }

        // The server-side probe can wait for background E-Ink sync to yield (up to ~2 minutes).
        // Poll the status endpoint so the page reflects progress instead of appearing stuck.
        function startPolling() {
          stopPolling();
          pollTimer = setInterval(async () => {
            try {
              const resp = await fetch(statusUrl, {cache: 'no-store', credentials: 'same-origin'});
              const st = await resp.json();
              if (st.state === 'SUCCEEDED') {
                stopPolling();
                statusBox.textContent = '验证成功，E-Ink 通道已恢复。';
                button.disabled = true;
              } else if (st.state === 'FAILED' || st.state === 'EXPIRED') {
                stopPolling();
                statusBox.textContent = st.lastError || '恢复探测未通过，请重新获取验证链接。';
                button.disabled = true;
                showRenewLink(refreshPath);
              } else if (st.state === 'WAITING' && st.verifyUrl &&
                         new URL(st.verifyUrl, window.location.href).pathname !== verifyPagePath) {
                stopPolling();
                statusBox.textContent = '原验证会话已更新，请打开新的验证链接。';
                button.disabled = true;
                showRenewLink(st.verifyUrl);
              } else if (st.state === 'VERIFYING') {
                statusBox.textContent = '服务器正在执行恢复探测（后台同步已让位，最多约 2 分钟）…';
              }
            } catch (_) { /* keep polling */ }
          }, 3000);
        }

        button.addEventListener('click', () => {
          statusBox.textContent = '正在打开验证码…';
          const captcha = new TencentCaptcha(captchaAppId, async (result) => {
            if (!result || result.ret !== 0) {
              statusBox.textContent = '验证已取消或未通过，可以再次点击重试。';
              return;
            }
            button.disabled = true;
            statusBox.textContent = '验证码通过，正在探测 E-Ink 通道（后台同步让位中）…';
            startPolling();
            try {
              const response = await fetch(completeUrl, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                cache: 'no-store',
                credentials: 'same-origin',
                body: JSON.stringify({ticket: result.ticket, randstr: result.randstr})
              });
              const payload = await response.json();
              stopPolling();
              statusBox.textContent = payload.message || (response.ok ? '恢复成功。' : '恢复失败。');
              if (!response.ok && payload.nextUrl) {
                showRenewLink(payload.nextUrl);
              } else if (!response.ok) {
                button.disabled = false;
              }
            } catch (_) {
              // Do not abort the server-side probe; keep polling until the status settles.
              statusBox.textContent = '提交响应中断，正在通过状态接口确认恢复结果…';
            }
          }, {});
          captcha.show();
        });
      </script>
    </body>
    </html>
""".trimIndent()

/**
 * 链接过期/已使用后的落地页：保留“获取新的验证链接”入口，避免机器人里的旧链接点开即死。
 */
internal fun einkVerificationExpiredHtml(refreshPath: String): String = """
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1">
      <title>微信读书 E-Ink 人工验证</title>
      <style>
        body { margin: 0; background: #f5f7fa; color: #172033; font-family: system-ui, sans-serif; }
        main { max-width: 520px; margin: 10vh auto; padding: 32px; background: white; border-radius: 16px; box-shadow: 0 10px 35px #17203318; }
        h1 { font-size: 20px; }
        .hint { color: #667085; line-height: 1.7; }
        a.button { display: block; margin-top: 18px; padding: 14px; border-radius: 10px; text-align: center; color: white; background: #07c160; font-size: 17px; text-decoration: none; }
      </style>
    </head>
    <body>
      <main>
        <h1>微信读书 E-Ink 人工验证</h1>
        <p class="hint">该验证链接已过期或已经使用。</p>
        <a class="button" href="${htmlEscape(refreshPath)}">获取新的验证链接</a>
      </main>
    </body>
    </html>
""".trimIndent()

/** 通用提示页（验证处理中 / 暂时无法生成链接等），同样保留续期入口。 */
internal fun einkVerificationNoticeHtml(message: String, refreshPath: String): String = """
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1">
      <title>微信读书 E-Ink 人工验证</title>
      <style>
        body { margin: 0; background: #f5f7fa; color: #172033; font-family: system-ui, sans-serif; }
        main { max-width: 520px; margin: 10vh auto; padding: 32px; background: white; border-radius: 16px; box-shadow: 0 10px 35px #17203318; }
        h1 { font-size: 20px; }
        .hint { color: #667085; line-height: 1.7; }
        a.button { display: block; margin-top: 18px; padding: 14px; border-radius: 10px; text-align: center; color: white; background: #07c160; font-size: 17px; text-decoration: none; }
      </style>
    </head>
    <body>
      <main>
        <h1>微信读书 E-Ink 人工验证</h1>
        <p class="hint">${htmlEscape(message)}</p>
        <a class="button" href="${htmlEscape(refreshPath)}">获取新的验证链接</a>
      </main>
    </body>
    </html>
""".trimIndent()
