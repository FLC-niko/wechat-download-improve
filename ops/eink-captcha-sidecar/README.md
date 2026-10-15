# E-Ink CAPTCHA sidecar

This directory contains two different local-only tools:

- `server.mjs` is the existing offline mock used by integration tests. It never contacts Tencent.
- `install-crack-tcaptcha.sh` installs the reviewed real `crack-tcaptcha` sidecar as a separate
  systemd service. GPL source is not copied into the Kotlin application.

## Install a reviewed version

The installer rejects branches, floating tags, and dirty checkouts. Obtain the exact commit from
the reviewed upstream revision, then run on the Ubuntu service host:

```bash
sudo env CRACK_TCAPTCHA_REF=<40-character-reviewed-commit> \
  bash ./ops/eink-captcha-sidecar/install-crack-tcaptcha.sh
sudoedit /etc/crack-tcaptcha.env
sudo systemctl enable --now crack-tcaptcha
curl http://127.0.0.1:9991/health
```

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

## Upgrade and rollback

Run the installer again with another reviewed 40-character commit, then restart the service. Never
configure production to follow upstream `main` or `latest`. To roll back the integration without
changing binaries, set `enabled: false` in the private Kotlin config and restart `wechat-download-kt`.

Before production use, complete the GPL-3.0-or-later compatibility review and the authorized test
run described in the service operations guide.
