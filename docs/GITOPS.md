# Detent GitOps channels

## Branches and promotion

- `preview`: day-to-day phone candidate. Feature/fix PRs target this branch.
- `nightly`: integration branch. Promote `preview` (or an explicit `integration/*` union)
  by PR; every candidate runs the full unit/network/native matrix before merging.
- `main`: stable source. Promote a qualified `nightly` by PR. CI/bootstrap-only PRs
  may target main directly, but their changed paths cannot include product source.
- `feat/*` / `fix/*`: isolated work, never a production installation channel.

No feature is pushed directly onto a channel and no history is force-pushed. An existing
unqualified feature PR is held rather than silently included in a union. Bootstrap the
new channel refs from the checked main foundation once, before enabling protections.
The single required check is **checks**; it fails if APK/unit/lint fails or an applicable
nightly integration lane is failed, cancelled or skipped. Configure required checks only
after that check has actually run successfully on the channel.

## CI and artifacts

`checks.yml` uses read-only permissions, verifies the exact PR head SHA, runs Python
pipeline contracts, Android unit tests and lint, then builds lab/test/phone APKs. A push
to preview produces an immutable `detent-candidate-<full-source-sha>` artifact containing:

- `detent-lab.apk` — `app.detent.launcher.lab`, for S22 qualification;
- `detent-lab-test.apk` — native instrumentation tests for the isolated lab package;
- `detent-kvn.apk` — `app.detent.launcher.kvn`, for the owner's S25;
- `provenance.json` — full source SHA, channel, run ID, version code and APK hashes.

Each application APK also embeds `assets/detent-build.json`. Its Android version name
includes the channel, workflow build number and source prefix. A locally built APK says
`local`; it is never silently called preview. CI version codes are `10000 + run_number`
from the candidate workflow, above the earlier manually numbered phone builds.

`integration.yml` runs Android API **29 and 36**, the full unit suite with real public
collection smoke enabled, lint and native instrumentation. Scheduled/manual runs explicitly
check out `nightly`; PR calls test GitHub's merge ref so the *combined* integration tree
is exercised. KVM/emulator failures are failures, never a skipped green acceptance gate.
JUnit, source identity, screenshots and logs are retained as artifacts. The daily schedule
is 07:37 UTC. Scheduling creates no automatic phone installation or stable promotion.

`build.yml` is now a separate, tag-only stable publisher. It requires the tag to be contained
in main and refuses publication without the stable release signing secrets. No PR build
receives the phone/release signing key. Provisioning a stable release is separate from
local phone signing; unprovisioned stable signing must remain an explicit failure.

## S22 → S25 deployment

1. Start from a successful **push** run of `checks.yml` at the current remote `preview`
   head. Download its exact artifact and verify with `scripts/provenance.py verify`.
   PR, local, nightly and stale-preview artifacts are not accepted for phone deployment.
2. Re-sign the checked APKs locally with the existing fork key, using `zipalign` and
   `apksigner`. Keep the key/password out of Git, CI, logs and chat. Never uninstall an
   installed package to get past a certificate mismatch. Signing must not change payloads.
3. Use `scripts/deploy-phone.py --target s22 ...` to update the lab package. The tool
   validates physical serial, CI run/ref, embedded identity, payload equality, package,
   version and signing-certificate equality, and backs up installed bytes/preferences.
4. Exercise collection scan, image import, offline/local-file restart, duplicate import,
   swipe up/down, distant-tap transitions and crash-free operation on the S22. Capture
   screenshots and logs. Hidden displays must keep owner keyboard/top focus on display 0;
   they do not qualify Android live-wallpaper transparency.
5. Write an S22 qualification receipt from the *observed* journeys (schema 1, exact
   source/run, preview channel, hardware serial `RFCTA1RZAQY`, both original artifact
   hashes, evidence directory, and every named journey `passed`). A unit test or authored
   script is not physical-phone evidence. Never invent this receipt to unblock deployment.
6. Use `scripts/deploy-phone.py --target s25 --qualification <receipt> ...`. It refuses
   a missing, failed, partial or wrong-source S22 receipt. It updates in place, never
   changes HOME, launches a screen, clears app data or uninstalls. Readback verifies the
   installed APK hash/identity and unchanged preferences. Keep `installation.json` plus
   before/after APK and preference backups in private operational storage, not public Git.

The two APK package IDs differ, so S22 is behavioral acceptance of the same source/build,
not byte equality between lab and S25. Signed APKs preserve the original ZIP payload;
the installation receipt records the final signed hash and signing certificate.

## Commands

```sh
# Feature PR
gh pr create -R kvnloo/detent --base preview --head feat/my-change

# Full integration promotion, then stable promotion after its native gates
gh pr create -R kvnloo/detent --base nightly --head preview
gh pr create -R kvnloo/detent --base main --head nightly

# Read an installed build without opening or moving the home task
adb -s SERIAL shell dumpsys package app.detent.launcher.kvn
# Pull the exact `pm path` APK, inspect assets/detent-build.json, and hash the bytes.
```

Reference patterns inspected: `kvnloo/t3code` channel refs and trusted preview publishing,
`kvnloo/hermes-agent`'s fast CI/strict full acceptance split and protected publication,
and `kvnloo/hermes-tern`'s exact-source candidate/provenance and integration/nightly checks.
This adapts their trust boundaries; it does not copy upstream-only deployment triggers or
introduce another phone scheduler.
