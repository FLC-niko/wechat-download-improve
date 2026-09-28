package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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

fun Route.weReadEinkVerificationRoutes() {
    get("/weread/eink/verify/{nonce}") {
        val nonce = call.parameters["nonce"].orEmpty()
        if (!WeReadEinkHumanVerification.canOpen(nonce)) {
            // 链接过期/已使用后不再返回纯文本死路：页面保留“获取新的验证链接”入口，
            // 与探测失败后的 nextUrl 体验一致，机器人里点开的旧链接也能一键换新。
            call.respondText(
                einkVerificationExpiredHtml(),
                status = HttpStatusCode.Gone,
                contentType = ContentType.parse("text/html; charset=UTF-8"),
            )
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.response.header("Pragma", "no-cache")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self' https:; script-src 'self' 'unsafe-inline' https:; " +
                "style-src 'self' 'unsafe-inline'; frame-src https:; connect-src 'self' https:; img-src 'self' data: https:",
        )
        call.respondText(
            einkVerificationHtml(nonce),
            contentType = ContentType.parse("text/html; charset=UTF-8"),
        )
    }

    post("/api/weread/eink/verify/{nonce}/complete") {
        val nonce = call.parameters["nonce"].orEmpty()
        val body = runCatching { call.receive<Map<String, String>>() }.getOrElse {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("ok" to false, "code" to "INVALID_BODY", "message" to "请求格式无效"),
            )
            return@post
        }
        val result = withContext(Dispatchers.IO) {
            WeReadEinkHumanVerification.complete(nonce, body["ticket"], body["randstr"])
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

    get("/api/weread/eink/verify/status") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.respond(WeReadEinkHumanVerification.status(AppConfig.siteUrl))
    }

    get("/weread/eink/verify/refresh") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        val current = WeReadEinkHumanVerification.status(AppConfig.siteUrl)
        when (current.state) {
            WeReadEinkVerificationState.WAITING -> {
                // 已有未过期的等待链接：直接复用，避免不断生成新挑战。
                val url = current.verifyUrl
                if (!url.isNullOrBlank()) {
                    call.respondRedirect(url)
                    return@get
                }
            }
            WeReadEinkVerificationState.VERIFYING -> {
                call.respondText(
                    einkVerificationNoticeHtml("上一次验证正在处理中，请稍候片刻再打开链接查看结果。"),
                    contentType = ContentType.parse("text/html; charset=UTF-8"),
                )
                return@get
            }
            else -> Unit
        }
        // IDLE / EXPIRED / FAILED / SUCCEEDED 均允许生成新链接：过期后一键续期。
        val verification = WeReadEinkHumanVerification.createOrReuse(AppConfig.siteUrl)
        val verifyUrl = verification.verifyUrl
        if (verifyUrl.isNullOrBlank()) {
            call.respondText(
                einkVerificationNoticeHtml("暂时无法生成新的验证链接，请稍后再试。"),
                status = HttpStatusCode.ServiceUnavailable,
                contentType = ContentType.parse("text/html; charset=UTF-8"),
            )
            return@get
        }
        call.respondRedirect(verifyUrl)
    }

    post("/api/weread/eink/verify/start") {
        if (!WeReadEinkRuntime.requiresHumanVerification()) {
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
                "verification" to WeReadEinkHumanVerification.createOrReuse(AppConfig.siteUrl),
            ),
        )
    }
}

internal fun einkVerificationHtml(nonce: String): String = """
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
      <script src="https://ssl.captcha.qq.com/TCaptcha.js"></script>
    </head>
    <body>
      <main>
        <h1>微信读书 E-Ink 人工验证</h1>
        <p class="hint">点击后完成腾讯验证码。成功结果会直接交给当前服务器的 E-Ink 会话，并立即执行恢复探测。</p>
        <button id="verify" type="button">开始验证</button>
        <div id="status" role="status"></div>
      </main>
      <script>
        const button = document.getElementById('verify');
        const statusBox = document.getElementById('status');
        const completeUrl = '/api/weread/eink/verify/$nonce/complete';
        const statusUrl = '/api/weread/eink/verify/status';
        let pollTimer = null;

        function stopPolling() {
          if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
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
                button.disabled = false;
              } else if (st.state === 'VERIFYING') {
                statusBox.textContent = '服务器正在执行恢复探测（后台同步已让位，最多约 2 分钟）…';
              }
            } catch (_) { /* keep polling */ }
          }, 3000);
        }

        button.addEventListener('click', () => {
          statusBox.textContent = '正在打开验证码…';
          const captcha = new TencentCaptcha('2044038556', async (result) => {
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
                const link = document.createElement('a');
                link.href = payload.nextUrl;
                link.textContent = '打开新的验证链接';
                link.style.display = 'block';
                link.style.marginTop = '12px';
                statusBox.appendChild(link);
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
internal fun einkVerificationExpiredHtml(): String = """
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
        <a class="button" href="/weread/eink/verify/refresh">获取新的验证链接</a>
      </main>
    </body>
    </html>
""".trimIndent()

/** 通用提示页（验证处理中 / 暂时无法生成链接等），同样保留续期入口。 */
internal fun einkVerificationNoticeHtml(message: String): String = """
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
        <p class="hint">$message</p>
        <a class="button" href="/weread/eink/verify/refresh">获取新的验证链接</a>
      </main>
    </body>
    </html>
""".trimIndent()
