# E-Ink CAPTCHA sidecar

This directory contains two different local-only tools:

- `server.mjs` is the existing offline mock used by integration tests. It never contacts Tencent.
- `install-crack-tcaptcha.sh` installs the reviewed real `crack-tcaptcha` sidecar as a separate
  systemd service. GPL source is not copied into the Kotlin application.

## Install a reviewed version

Prerequisites: Python 3.11+ with venv support, Node.js 18+, npm, Git, systemd, and the existing
`topview` service account (`User=` in the unit). The installer checks the pinned commit's
`requires-python` before checking it out or installing it; an incompatible interpreter is rejected.

The installer rejects branches, floating tags, and dirty checkouts. Obtain the exact commit from
the reviewed upstream revision, then run on the Ubuntu service host:

```bash
sudo env CRACK_TCAPTCHA_REF=<40-character-reviewed-commit> \
  bash ./ops/eink-captcha-sidecar/install-crack-tcaptcha.sh
sudoedit /etc/crack-tcaptcha.env
sudo systemctl enable --now crack-tcaptcha
curl http://127.0.0.1:9991/health
```

The Python package is installed normally into the venv. The unit explicitly sets
`TCAPTCHA_TDC_JS_DIR=/opt/crack-tcaptcha/source/src/crack_tcaptcha/tdc/js`, matching the directory
where `npm ci` installs jsdom. Do not override this path in `/etc/crack-tcaptcha.env`. A missing
`package-lock.json` aborts installation. Installation also checks Python imports, model/font file
readability and Node/jsdom loading as the actual service user. These are local dependency checks;
they do not submit a captcha or prove recognition accuracy.

## Offline smoke test

Before deploying a real sidecar, run the end-to-end contract and fallback smoke test locally:

```bash
cd wechat-download-kt
./gradlew einkAutoRecoverySmokeTest
```

On Windows use `gradlew.bat einkAutoRecoverySmokeTest`. The test starts an in-process fake
`/solve` endpoint, verifies ticket injection and `RECOVERED`, then simulates a crashed sidecar and
verifies that a current manual-review URL remains available. It never contacts Tencent and never
sends a webhook.

The smoke test pauses both `/solve` and the recovery probe and asserts zero manual notifications
while either is pending. No notification is sent after `RECOVERED` either. Other diagnostic tests
cover disabled/excluded accounts, authentication errors, bad responses, timeout, account isolation,
concurrent entry and stale sessions after reload.

It also crashes the recovery probe, checks that the manual notification receives a fresh nonce,
and completes a simulated human submission to recover successfully. Both status and evaluation
snapshots are checked for ticket leakage. The fallback decision uses the same helper as the main
runtime; webhook delivery itself is replaced by an in-memory notification sink.

The HTML report is `wechat-download-kt/build/reports/tests/einkAutoRecoverySmokeTest/index.html`.
This validates integration and failure handling, not the real solver's recognition accuracy.

Use the same secret in the ignored `wechat-download-kt/config.yaml`:

```yaml
weread:
  eink:
    human_verify:
      auto_solver:
        enabled: true
        url: "http://127.0.0.1:9991"
        secret: "<same value as TCAPTCHA_SERVE_SK>"
        timeout_seconds: 75
        retries: 1
        vid_allowlist:
          - "<authorized-test-vid>"
```

The Kotlin service makes one automatic attempt for an allowlisted challenge. Any solver or recovery
failure immediately falls back to the existing human verification link and QR notification. The
sidecar is optional and is not part of the Kotlin service readiness check.

## Distinguish installed, running and enabled

Git updates and the Android "启动 AAOS 服务" button do not start or enable this server-side feature.
Check all three independently:

1. `systemctl status crack-tcaptcha --no-pager` and `curl --max-time 5 http://127.0.0.1:9991/health`
   prove the process is listening. `/health` is not a captcha or TDC dependency acceptance test.
2. Set `auto_solver.enabled: true` in the Kotlin process's actual `config.yaml`, not the example.
   Environment variables override YAML. Restart Kotlin after configuration changes.
3. Add the authorized test `vid` to `vid_allowlist`. An empty list never enables all accounts.

Environment names are `WEREAD_EINK_HUMAN_VERIFY_AUTO_SOLVER_ENABLED`,
`WEREAD_EINK_HUMAN_VERIFY_AUTO_SOLVER_VID_ALLOWLIST` (comma separated), and
`WEREAD_EINK_HUMAN_VERIFY_AUTO_SOLVER_SECRET`. Do not print the secret when inspecting configuration.

In the existing `/api/weread/eink/status` response, inspect `runtime.accounts[].auto_solver`:
`enabled`, `allowlisted`, `eligible`, `phase`, `result`, `reason`, `started_at`, `finished_at`,
`duration_ms`, `attempts`. All are in memory; reading status never calls the solver. Timestamps are
Unix milliseconds. Result and reason describe the last event, while enabled/allowlisted reflect
the current effective configuration. `IDLE` means no attempt is recorded, not solver health.

Phases: `IDLE` → `SOLVING` → `PROBING` → `RECOVERED` or `MANUAL_REQUIRED`. Disabled/excluded
events go directly to `MANUAL_REQUIRED`, with `DISABLED` / `NOT_ALLOWLISTED`. Interrupted account
reloads record `SESSION_CHANGED`; obsolete tasks cannot notify or update replacement diagnostics.
Existing reminder throttling still applies. Manual notifications retain the event name/link/QR and
add `auto_solver_result` plus `auto_solver_reason`; solver raw errors are never forwarded.

### Remote diagnosis without SSH

`runtime.auto_solver_diagnostics_version: 1` confirms this diagnostic code is present in the
running binary. Each account's `auto_solver` also reports `secret_configured` (boolean only),
`attempt_id` (a new UUID, never the challenge nonce) and at most 32 `recent_events` in chronological
order. These bounded in-memory events survive an account reload, but not an application restart.
Reading the status endpoint never calls `/solve`, triggers a captcha, or sends notifications.

The `WeReadEinkAutoSolverLog` logger writes the same events at INFO level to the existing
console and `logs/app.log` (or `LOG_DIR` override). Search for `EINK_AUTO_SOLVER`:

```bash
grep 'EINK_AUTO_SOLVER' logs/app.log | tail -n 50
```

Events distinguish `CONFIG`, `TRIGGERED`, `SOLVE_STARTED`, `SOLVE_RESULT`, `PROBE_STARTED`,
`FINISHED`, `MANUAL_NOTIFY_STARTED`, `MANUAL_NOTIFY_FINISHED`, suppression, and obsolete tasks.
Follow `attemptId` in JSON / `attempt_id` in log lines to correlate one attempt.
`SOLVE_RESULT` includes the HTTP status, `solverResult`, time and a fixed `failure` category:
`TIMEOUT`, `CONNECTION_FAILED`, `IO_ERROR`, `HTTP_ERROR`, `INVALID_RESPONSE`, `SOLVER_REJECTED`,
`CONFIG_ERROR` or `UNEXPECTED_ERROR`. No exception message, raw body, URL, key or ticket is retained.
`MANUAL_NOTIFY_FINISHED` records `textSent` and `qrSent`; false means delivery did not succeed
(which may include notification throttling), not that solving is still running.

Use these checks after deployment:

- Missing diagnostic version: verify the running artifact and Git auto-deployment result.
- `CONFIG enabled=false` or `allowlisted=false`: automatic solving will be skipped.
- `IN_FLIGHT_SUPPRESSED` / `REMINDER_SUPPRESSED`: the event was gated; no new solver call started.
- `SOLVE_RESULT httpStatus=401/403`: check sidecar authentication configuration.
- `SOLVE_RESULT failure=CONNECTION_FAILED/TIMEOUT`: check sidecar process, port and response time.
- `solverResult=SOLVED` followed by `FINISHED result=PROBE_FAILED`: recognition returned a ticket,
  but the WeRead recovery probe did not succeed. Inspect the account's `last_probe_result` too.
- `FINISHED result=RECOVERED`: the full automatic verification and probe succeeded; there must be
  no manual notification for that attempt.

After deployment, verify one real `-2041 → RECOVERED` on an authorized test account and one failure
that sends a usable manual link. Until then real recognition and real webhook delivery remain
**not accepted**. Passing mock tests alone does not change this status.

## Upgrade and rollback

Run the installer again with another reviewed 40-character commit, then restart the service. Never
configure production to follow upstream `main` or `latest`. To roll back the integration without
changing binaries, set `enabled: false` in the private Kotlin config and restart `wechat-download-kt`.

Before production use, complete the GPL-3.0-or-later compatibility review and the authorized test
run described in the service operations guide.
