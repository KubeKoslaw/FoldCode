# FoldCode — plan

VS Code running natively on Android as an APK, tuned for the **Honor Magic V3** foldable and for coding by touch.

The "port" works like this. VS Code's desktop shell is Electron, and Electron doesn't run on Android. VS Code itself is split into two halves, though:

- the **workbench** (the whole UI: Monaco editor, sidebar, terminal view, extensions UI) is web code, and
- the **backend** (Node.js, extension host, terminal processes, file system, git) is a normal program.

Desktop VS Code works the same way (Electron = a Chromium window + Node.js processes talking locally). vscode.dev and Remote-SSH already use this split. FoldCode keeps both halves on the phone. The backend runs natively on Android's own libc (bionic), with no proot and no VM, in the app's own Linux prefix. The workbench runs in Android System WebView. A native Kotlin shell around them handles the fold, the touch keyboard and app lifecycle.

**The backend is not a remote server.** It is a process inside the APK, reachable only on 127.0.0.1 with a random per-launch password. No PC, network or second app is involved; the PC-side apt repo exists only during development.

---

## 1. Decisions so far

| Topic | Decision | Why |
|---|---|---|
| Runtime | **Native bionic, no proot** | Fastest file I/O, fork/exec and startup. The cost: every tool must be built for Android, and binaries that extensions bundle for glibc Linux won't run as-is (see §5). |
| VS Code base | **code-server (Coder, MIT), forked, with our own patch set on top** | It tracks upstream VS Code monthly, already runs on Termux/bionic (it's packaged in the Termux User Repository, TUR), uses Open VSX, and already patches VS Code with a quilt patch series, which is where our touch and fold patches go. Alternatives (openvscode-server, a raw Code-OSS `reh-web` build) mean maintaining more ourselves. |
| Extensions | **Open VSX only** | Microsoft's Marketplace terms forbid using it outside Microsoft's own VS Code builds. Proprietary Microsoft extensions get open replacements: Pylance → basedpyright, ms-vscode.cpptools → clangd, CodeLLDB → lldb-dap. |
| UI host | **Android System WebView** (Chromium, updated from Play Store), one Activity | Same engine VS Code web is tested on. It also lets us bridge fold and keyboard events into the workbench. |
| Input | **Touch first** | Our own in-app **code keyboard** (a native view, not a system IME) sends keys straight to the WebView. That skips the IME composition bugs Gboard and other keyboards cause in Monaco. There's also an extra-keys bar for when the system keyboard is in use, plus touch gestures in the editor. |
| Fold features | **Laptop mode (half-folded), seamless fold/unfold, split preview** | The outer-screen compact mode wasn't picked, so the folded screen gets a sensible responsive layout but no special work. |
| Languages | **PHP, SQL, HTML/CSS/JS, JS/TS (Node), Python, C/C++, Rust, Go** | Section 4 lists the packages and extensions for each. |
| Distribution | **Sideload / GitHub Releases, `targetSdk 28`** | Like Termux from F-Droid/GitHub, the app can run binaries from its own data directory. Android 10+ blocks that for `targetSdk ≥ 29`. It can't go on Google Play. |
| SDK levels | `compileSdk 36`, `targetSdk 28`, `minSdk 30`, **arm64-v8a only** | One device class (Android 11+ phones). API 30 brings the hinge-angle sensor, which is the fallback for fold detection. |
| Packages | **Small bootstrap in the APK + our own apt repo** | The APK carries bash, coreutils, apt, git, openssh, Node.js LTS, ripgrep and code-server. Everything else comes from `pkg install …` out of our repo on GitHub Releases/Pages, so packages update without a new APK. |
| Package build | **termux-packages in Docker, rebuilt with `TERMUX_APP__PACKAGE_NAME=io.fold`** | Termux packages hard-code `/data/data/com.termux/...`, so the official repos can't be used. All packages are rebuilt for our prefix with the same build system. termux-packages is pinned to commit `99a6d657` (2026-11-10); our changes live as patches in `packages/patches/` and our recipes in `packages/recipes/`, applied by `scripts/setup-termux-packages.sh`. |
| Host safety (phase 1) | **Patch out `run-docker.sh`'s `pid_max` change** | Upstream may lower `/proc/sys/kernel/pid_max` on the *host* through sudo. We build only aarch64, which doesn't need it, so the patch skips it. |
| apt repo (phase 1) | **Own signed repo; the app writes `sources.list`, the key and `TERMUX_PKG_NO_MIRROR_SELECT=1`** | The repo URL isn't baked into any package, so moving the repo needs no rebuild. The signing key lives in its own keyring in `keys/` (git-ignored), never in a personal `~/.gnupg`. Its public half ships in the APK (`assets/foldcode-repo.gpg`). `pkg` would otherwise switch to Termux mirrors, whose packages are built for com.termux. |
| Dev repo (phase 1) | **Served from the PC on localhost and reached by the phone through `adb reverse tcp:8686`** | Nothing is opened on the LAN, and it works over the wireless adb link. The code repo is private, so public package hosting will be a separate public repo (GitHub Releases/Pages). |
| AI chat panel (phase 3) | **Hidden by default, one tap to open** | Chosen. `workbench.secondarySideBar.defaultVisibility: hidden`; AI features stay available, and CLI agents in the terminal are unaffected. |
| Theme (phase 3) | **Follows Android's dark mode** | Chosen. `window.autoDetectColorScheme` plus the WebView's force-dark setting in web-theme-only mode, so VS Code's `prefers-color-scheme` matches the system. |
| First start (phase 3) | **Download the environment from GitHub at first start** | Chosen. A small APK, but first start needs internet. The bootstrap URL and SHA-256 are pinned in the APK (`app/bootstrap.properties` → BuildConfig); the download resumes and is verified before installing. |
| Background (phase 3) | **Backend keeps running in a foreground service with a Stop action** | Chosen. Terminals, dev servers and tasks survive app switches; the service restarts a crashed backend (3× in 2 min max). |
| Public packages (phase 3) | **github.com/KubeKoslaw/foldcode-packages (public)** | Confirmed. It holds only README, the public repo key and the build sources (termux-packages commit, patches, recipes, as GPL requires). Packages are the assets of release `stable` (flat repo `deb …/releases/download/stable/ ./`, safe file names, no `-static`); the bootstrap is in release `bootstrap-<sha prefix>`. Single author (noreply), same privacy hooks. The app code repo stays private. |
| App id / name | **`io.fold`**, display name **FoldCode** | The termux-packages wiki advises ≤ 10 characters because of Unix socket path limits under `/data/data/<id>`. **Permanent**: packages built for one id can't be moved to another. |
| Paths on the phone | prefix `/data/data/io.fold/files/usr`, home `/data/data/io.fold/files/home` | Same layout as Termux, so its build recipes work unchanged. |
| Extras | **Git + GitHub sign-in, access to phone files, AI CLI agents in the terminal** | Remote mode to the PC wasn't picked. |
| Native UI toolkit | Kotlin + plain Android Views (no Compose) | Low RAM and a small APK. The heavy UI is the workbench anyway. |
| Keyboard delivery (phase 0, 2026-11-10) | **Window flag `FLAG_ALT_FOCUSABLE_IM` + the WebView's own `InputConnection`**: `commitText` for text, `sendKeyEvent` for keys and shortcuts. The JS bridge is the fallback for app-level commands | Measured in the phase 0 spike (§8). It's the only combination that passed every test (17/17, including Polish letters and Ctrl shortcuts) **with the system keyboard never appearing**, and all DOM events stay trusted. |

**Not chosen** (can come back later): proot/glibc distro, Termux as a backend, remote mode to the PC, Google Play, an outer-screen compact mode.

**Build machine:** Linux x86_64 with Docker, Android SDK (platform 36, build-tools 36.1.0, NDK 26.1, CMake 3.22), JDK 25, Node 26, Python 3.14. The phone is reached over **wireless adb** (QR pairing). USB adb needs a udev rule for vendor `339b`.

**Target device** (read over adb, 2026-11-10): Honor Magic V3 `FCP-N49` (EEA), **MagicOS 11.0.0 on Android 17 (API 37)**, security patch 2026-10-01, 11 GB RAM, arm64-v8a only, WebView 156.
- Inner screen: 2156×2344 px, 480 dpi (719×726 dp in portrait)
- Outer screen: 1060×2376 px (353×737 dp)
- Sensors: hinge angle, posture and "extend hall"

---

## 2. Architecture

```
┌──────────────────────── FoldCode APK (io.fold, targetSdk 28) ─────────────────────────┐
│ MainActivity (single Activity, survives fold/unfold without restart)                   │
│  ┌───────────────────────────────┐  ┌────────────────────────┐                         │
│  │ Workbench WebView              │  │ Preview WebView         │ ← split pane when      │
│  │  VS Code UI (code-server)      │  │  http://127.0.0.1:8000  │   unfolded / book mode │
│  │  + host bridge + touch layer   │  └────────────────────────┘                         │
│  └───────────────────────────────┘                                                      │
│  CodeKeyboard (native)  ExtraKeysBar  PostureController (Jetpack WindowManager + hinge) │
│  SetupActivity (bootstrap unpack)     RecoveryTerminal (terminal-view, if backend fails) │
│  BackendService (foreground) ──spawns──► node code-server --bind 127.0.0.1:<random>      │
└─────────────────────────────────────────────┬──────────────────────────────────────────┘
                                              │
     /data/data/io.fold/files/usr    bionic prefix built from termux-packages
        bin/{bash,node,git,ssh,apt,pkg,rg}  lib/code-server/…
        + pkg install php mariadb python clang rust golang …
     /data/data/io.fold/files/home   projects, ~/.local/share/code-server, extensions
        ~/storage/{shared,downloads,documents} → /sdcard (All files access)
```

Key pieces:

- **BackendService** is a foreground service with a persistent notification and a Stop action. It starts `code-server` with the right environment (`PREFIX`, `HOME`, `TMPDIR`, `PATH`, `LANG`), on a random localhost port with a random per-launch password. The app puts the auth cookie into the WebView's `CookieManager`, so no password is ever typed and other apps on the phone can't open the editor. It also health-checks the backend, restarts it after a crash and keeps a log.
- **Host bridge.** Kotlin exposes `window.FoldHost` to the workbench through `addJavascriptInterface`. A small browser-side workbench contribution (one of our code-server patches) receives posture changes and keyboard routing, and calls VS Code commands: toggle the sidebar, move the panel, change font size, open the preview.
- **Key injection** (settled in phase 0, see §8). While the CodeKeyboard is active, the window sets `FLAG_ALT_FOCUSABLE_IM`, so the system keyboard can't show. The CodeKeyboard then talks to the WebView's own `InputConnection` the way an IME would: `commitText` for any text, Polish letters included, and `sendKeyEvent` for keys and shortcuts. Chromium turns these into *trusted* DOM events, so VS Code keybindings, Monaco and xterm.js behave as with a real keyboard. Switching to Gboard just clears the flag.
- **RecoveryTerminal** is a plain native terminal (Termux's `terminal-emulator`/`terminal-view` libraries, Apache-2.0). It opens when the backend can't start, so the environment can always be repaired.

---

## 3. Fold and touch design

**Postures**, read from Jetpack WindowManager (`FoldingFeature`: state, orientation, hinge bounds). If MagicOS doesn't report them, the fallback is `Sensor.TYPE_HINGE_ANGLE` plus display size.

| Posture | Layout |
|---|---|
| **Folded** (outer screen) | Phone layout: sidebar and panel collapsed, editor full width. Plain responsive, no special mode. |
| **Unfolded flat** (inner screen) | Full laptop-like workbench: activity bar, sidebar, editor, panel. Optional **split preview** on one side with a draggable divider. |
| **Laptop mode** (half-open, hinge horizontal) | Split exactly at the hinge bounds, so nothing sits on the crease. **Top:** the workbench. **Bottom:** the CodeKeyboard with a trackpad strip for moving the cursor. One tap swaps the bottom to terminal or preview. |
| **Book mode** (half-open, hinge vertical) | Editor on one half, preview or terminal on the other. Falls out of the split preview. |

**Seamless fold/unfold.** The manifest handles `screenSize|smallestScreenSize|screenLayout|orientation|density|keyboard|keyboardHidden|uiMode` itself, so the Activity and the WebView are never recreated. Each posture has a layout preset that applies automatically and remembers manual tweaks. If Android kills the Activity anyway, the backend keeps running in its service and VS Code restores open editors on reload.

**Split preview.** A second native WebView. It opens automatically when a dev server starts listening (`php -S`, Vite, Flask and so on). It has reload, a mobile/desktop viewport toggle and an optional in-page console (Eruda). Debug builds allow `chrome://inspect` from the PC.

**CodeKeyboard** is a native view inside the app, not a system IME:

- A QWERTY layer and a symbols layer tuned for code: `{} [] () <> ; : = + - * / \ | & ! ? " ' ` $ # @ % ^ ~ _`
- Sticky Ctrl, Alt and Shift, plus Esc, Tab, arrows, Home/End and PgUp/PgDn
- Undo/redo and command-palette keys
- Swipe on space to move the cursor
- Long-press for alternates, including Polish letters (ą ć ę ł ń ó ś ź ż)
- No autocorrect, haptic feedback

**Extra-keys bar.** A Termux-style row above the system keyboard, for when Gboard or the Honor keyboard is in use.

**Touch layer in the workbench** (our patches):

- Bigger hit targets
- Long-press opens the context menu, double-tap selects a word
- **Selection handles for Monaco** (Monaco has none on touch; this is the riskiest UX piece)
- Pinch to change editor font size, two-finger scroll
- Drag-to-select in the terminal
- A floating command-palette button

A touch settings profile comes on by default: bigger fonts, minimap off, wider scrollbars, sticky scroll.

---

## 4. Languages: packages and extensions

Packages come from our apt repo. Extensions come from Open VSX. "Path setting" means the extension's bundled glibc binary gets swapped for the bionic one from our prefix (see §5).

| Language | Packages (`pkg install`) | Extensions | Notes |
|---|---|---|---|
| PHP | `php`, `composer`, `php-fpm` (+ Xdebug if it builds) | Intelephense, PHP Debug | `php -S` dev server feeds the split preview. |
| SQL | `sqlite`, `mariadb`, `postgresql` | SQLTools + drivers (or Database Client) | The database servers run locally in the prefix. |
| HTML/CSS/JS | (built into VS Code) | Prettier, ESLint | Emmet, HTML, CSS and JSON support is built in. |
| JS/TS / Node | `nodejs-lts` (in the bootstrap), pnpm/yarn via corepack | built-in TypeScript | npm packages with native binaries need **android-arm64** builds. esbuild, Rollup and SWC have them. sharp, Prisma and similar may need workarounds. |
| Python | `python`, `pip`/`venv` | Python, Python Debugger, basedpyright, Ruff (path setting), Jupyter | Jupyter needs pyzmq on bionic (to check). |
| C/C++ | `clang` (includes clangd), `cmake`, `ninja`, `make`, `lldb`, `gdb` | clangd, lldb-dap, CMake Tools | clangd and lldb-dap path settings point at the prefix. |
| Rust | `rust`, `rust-analyzer` | rust-analyzer (path setting) | Debugging through lldb-dap. |
| Go | `golang` | Go | gopls via `go install` (Go builds for android/arm64 natively). Delve on Android is to check. |

Meta packages: `fold-php`, `fold-python`, `fold-web`, `fold-native` (C/C++/Rust/Go). The app also gets a **Language packs** screen that installs them with one tap, so apt is optional.

---

## 5. Known hard parts and risks

1. **Glibc-only binaries.** Open VSX has no Android target, so platform-specific extensions ship `linux-arm64` (glibc) or `alpine-arm64` (musl) binaries, and neither runs on bionic.
   Fix, step 1: settings presets that point extensions at our prefix's tools.
   Fix, step 2: an optional **glibc runner**, i.e. Termux's glibc-packages rebuilt for `io.fold`, for binaries with no bionic replacement. Some npm packages and AI CLI tools may need it too.
2. ~~**MagicOS fold reporting.**~~ **Resolved in phase 0:** MagicOS 11 reports book, laptop, flat and folded correctly through Jetpack WindowManager (§8). It flickers briefly during transitions, so the layout switch needs a ~400 ms debounce.
3. **Monaco on touch.** Text selection and cursor placement by finger need custom work in the editor (patches, not just CSS).
4. **Background killing.** Android 12+'s phantom process killer stops child processes beyond 32. MagicOS also kills background apps aggressively. The setup screen walks through:
   - battery optimization off
   - App launch → Manage manually
   - Developer options → Disable child process restrictions (Android 14+), or over adb: `device_config put activity_manager max_phantom_processes 2147483647`
5. **Build effort.** Rebuilding packages for our prefix takes hours of Docker builds; clang and Rust are the heaviest. Start with the minimal set.
6. **Upstream churn.** code-server releases monthly. Pin versions and rebase our patches on purpose.
7. **targetSdk 28 is now exactly at the limit.** The Magic V3's Android 17 has `ro.build.version.min_supported_target_sdk = 28`, so it installs only apps targeting 28 or higher. One more bump (Android 18?) would block FoldCode. The fallback is ready and tested: running binaries through `/system/bin/linker64` works on the phone (§8). That's termux-exec's mode, which lets us move to `targetSdk ≥ 29`. Plan for that switch by phase 2, so it isn't forced on us later.
8. **No Microsoft-only extensions.** Pylance, the C/C++ extension, Live Share and similar are out (Open VSX only).

---

## 6. Folder layout (planned)

```
foldcode/
  plan.md
  app/               Android app (Kotlin, plain Views)
  code-server/       our patch series over a pinned upstream code-server release
  packages/          termux-packages overlay: our recipes (code-server, fold-* meta packages), properties for io.fold
  scripts/           fetch-spike-web.sh, phone-phase0.sh, setup-termux-packages.sh, build-bootstrap.sh,
                     repo-key.sh, make-apt-repo.py, serve-repo.sh
  third_party/       (git-ignored) pinned termux-packages checkout
  keys/              (git-ignored) apt repo signing keyring
  web/               pinned npm deps for in-app web pages (now: Monaco + xterm.js for the spike)
  out/               (git-ignored) built .debs, apt index, bootstrap zip, APKs, phase 0 phone results
```

Building:

```
scripts/fetch-spike-web.sh      # once: copies Monaco + xterm.js into app assets
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
scripts/phone-phase0.sh         # phase 0 checks on the connected phone
scripts/build-bootstrap.sh      # phase 1: packages + bootstrap zip in Docker (log: out/build/bootstrap.log)
scripts/make-apt-repo.py        # out/debs → signed apt repo in out/repo
scripts/serve-repo.sh           # serve out/repo to the phone over adb reverse
```

---

## 7. Steps

### Phase 0 — Setup and probes
- [x] Create the Android project skeleton (`io.fold`, compile 36 / target 28 / min 30, arm64-v8a + x86_64 for the emulator)
- [x] Fold probe screen (`FoldProbeActivity`): logs `FoldingFeature` (state, orientation, bounds), hinge angle, window metrics and config, and paints the hinge split. Works on the emulator
- [x] Keyboard spike (`KeyboardSpikeActivity`): Monaco 0.57 + xterm.js 6.0 + a plain input, with 4 delivery methods × 3 system-keyboard modes and an automated suite. Run on the emulator, results in §8
- [x] Exec test: run a binary copied into the app's data dir (direct and through `linker64`). Works on the emulator
- [x] Foldable emulator AVD `foldcode36` (Pixel 9 Pro Fold profile, Android 16)
- [x] **Phone access over adb**: wireless debugging, paired by QR code. USB was refused because the device node is `root:usb` and there's no udev rule for vendor `339b`
- [x] Run `scripts/phone-phase0.sh` on the Magic V3: device facts, keyboard spike (`system` + `altfocus`) and the fold-probe walk through every posture. Results in §8 and `out/phase0/`

### Phase 1 — Package toolchain (Docker)
- [x] Clone termux-packages (pinned), set `TERMUX_APP__PACKAGE_NAME=io.fold`. Path limits: `/data/data/io.fold` is 17 characters against a 69 limit, fine
- [x] Build the core set in Docker (`scripts/build-bootstrap.sh`): Termux's default bootstrap (bash, coreutils, apt/dpkg, termux-tools/`pkg`, termux-exec…) + git, openssh, ripgrep → `out/bootstrap/bootstrap-aarch64.zip`
- [x] Bootstrap installer in the app (`io.fold.runtime.Runtime`): extract, permissions, symlinks, second stage, apt config. Test it on the phone with `BootstrapProbeActivity`
- [x] Repo signing key (`scripts/repo-key.sh`) and repo generator (`scripts/make-apt-repo.py`: flat pool, Packages, signed InRelease, no dpkg needed on the host)
- [x] Dev repo on the phone: `scripts/serve-repo.sh` (localhost + `adb reverse`), then `apt update` / `apt install` from it
- [x] nodejs-lts + npm into the repo and the bootstrap (verified on the phone)
- [ ] Public package hosting: a separate public GitHub repo for the apt repo (the code repo stays private)
- [ ] Language packs: php + composer, sqlite, mariadb, postgresql, python, clang/lldb/cmake/ninja/make/gdb, rust + rust-analyzer, golang, gh, plus the `fold-*` meta packages

### Phase 2 — VS Code backend on bionic
- [x] Pick the version: code-server **4.145.2** (VS Code `c4453f8e`) needs Node **24**, the same major as our nodejs-lts 24.22.2, so no second Node is needed
- [x] Recipe `packages/recipes/code-server/` (based on TUR's MIT recipe): VS Code built on the host, native modules (node-pty, spdlog, watcher, argon2…) cross-compiled for arm64, the prefix's `node` and `rg` instead of the bundled ones, and `bin/code-server`
- [x] Android user-data patch, written against the real `userDataPath.ts` (TUR's version wouldn't apply: it assumed `path.join(os.homedir())` and an unsubstituted `@TERMUX_HOME@`)
- [x] Build the `code-server` .deb in Docker (`scripts/build-packages.sh code-server`). VS Code compiled with 0 errors. The .deb is 104 MB (545 MB unpacked). Native modules (node-pty, spdlog, watcher, argon2, sqlite3, kerberos) are ARM64 and link bionic `libc.so`/`libc++_shared.so`; `lib/node` → prefix `node`, every bundled `rg` → prefix `rg`, no `com.termux` paths
- [ ] Trim the package: ~170 MB is GitHub's bundled Copilot SDK with linux-x64/musl/Windows binaries that never load on Android
- [x] Backend launcher `io.fold.runtime.Backend`: 127.0.0.1, random port, random per-launch password (`--auth password`), `/healthz` wait, automatic login cookie into the WebView. Cleartext HTTP is allowed only for 127.0.0.1/localhost (`res/xml/network_security_config.xml`)
- [x] Smoke-test screen `VsCodeProbeActivity` (backend + WebView, workbench check with `--ez autotest true`)
- [ ] Extension target platform + Open VSX defaults; check that `process.platform === "android"` doesn't break other VS Code paths
- [ ] Add code-server to the bootstrap
- [x] Smoke test on the phone: backend inside the app, workbench in a WebView (results in §10)

### Phase 3 — Android shell
- [x] First start in `MainActivity`: download (`BootstrapDownloader`: https only, resumable, size + SHA-256 check), install, write the VS Code defaults (`VsCodeDefaults`)
- [x] `BackendService` + `BackendHolder`: foreground service with a Stop action, random port + password, health check, login cookie, restart on crash, log in `files/logs/code-server.log`
- [x] `MainActivity`: full-screen workbench WebView. Outside links open in the browser, file chooser for uploads, Back leaves the app without stopping VS Code, no reload on fold/unfold. Probes moved to the launcher long-press "Diagnostics"
- [x] Clipboard bridge (`HostBridge`): `navigator.clipboard` goes to Android's ClipboardManager over a WebMessageListener limited to the backend's origin
- [x] Publish: bootstrap now includes code-server (260 MB, 107 packages); `scripts/publish-packages.sh` uploads the repo and bootstrap and writes `app/bootstrap.properties`
- [x] Fresh-install test on the phone: uninstall, install, launch, VS Code ready in **43 s** (results in §11)
- [ ] Notifications: MagicOS blocks them for this app by default and never asks (targetSdk 28 can't request them). Add an in-app "Enable notifications" banner that opens the app's notification settings
- [ ] Downloads from VS Code (blob URLs) and `window.open`
- [ ] Trim the bootstrap: the Copilot SDK binaries for other platforms (~170 MB unpacked)
- [ ] RecoveryTerminal (terminal-view)
- [ ] App settings: font size, keyboard options, keep-awake, backend logs

### Phase 4 — Touch-first input
- [ ] CodeKeyboard: layers, sticky modifiers, cursor swipe, long-press alternates with Polish letters, haptics
- [ ] Extra-keys bar above the system keyboard
- [ ] Workbench touch patches: hit targets, long-press menu, double-tap select, **Monaco selection handles**, pinch font size, terminal selection, command-palette button
- [ ] Default touch settings profile

### Phase 5 — Fold modes
- [ ] PostureController → bridge → workbench contribution
- [ ] Laptop mode: split at the hinge, workbench on top, CodeKeyboard + trackpad strip below (swappable to terminal/preview)
- [ ] Seamless fold/unfold: no restarts, per-posture layout presets that remember tweaks
- [ ] Split preview pane: divider, auto-open on a new dev-server port, reload, viewport toggle, optional Eruda
- [ ] Book mode: editor | preview or terminal

### Phase 6 — Extras
- [ ] Git + GitHub: `gh` device-flow sign-in from a button, `gh auth setup-git`, clone dialog
- [ ] Phone files: All files access, `~/storage` symlinks, open folders from /sdcard, "Open with FoldCode" intents, a DocumentsProvider for home
- [ ] AI CLI agents: global npm installs into the prefix, working node-gyp, test that popular agents start in the terminal, and list any that need glibc
- [ ] Language packs screen (one-tap install of `fold-*`)

### Phase 7 — Compatibility and polish
- [ ] Extension path presets: clangd, rust-analyzer, ruff, gopls, lldb-dap
- [ ] Optional glibc runner for glibc-only binaries
- [ ] MagicOS survival setup screen: battery, App launch, phantom processes
- [ ] Performance pass: startup time, RAM, WebView GPU, battery drain
- [ ] Release pipeline: signed APK on GitHub Releases, repo publish script, in-app update check

---

## 8. Phase 0 findings

### Emulator (2026-11-10): Pixel 9 Pro Fold profile, Android 16 (API 36), WebView 133

**Exec from the app's data dir works with `targetSdk 28`.** A binary copied into `files/` runs directly (`exit=0`) and through `/system/bin/linker64`. This is the base assumption of the bionic runtime.

**Fold detection.** Jetpack WindowManager reports every posture correctly:

| Posture | What WindowManager reports |
|---|---|
| Book (half-open, portrait) | `HALF_OPENED` + `VERTICAL`, hinge at x=1038 |
| Laptop (half-open, rotated) | `HALF_OPENED` + `HORIZONTAL`, hinge at y=1038 |
| Flat | `FLAT`, `isSeparating=false` |
| Folded | no fold feature; the hinge sensor reads 0°, so the classifier now treats < 30° as FOLDED |

The hinge-angle sensor works as the fallback. The laptop split lands exactly on the hinge bounds.

**Keyboard spike.** 17 cases each: Monaco text/Polish/Backspace/arrows/Home/Enter/Tab/Ctrl+S/Ctrl+Shift+P/Ctrl+Z, xterm.js text/Polish/control keys/Ctrl+C, plain input text/Polish/editing.

| Method | System keyboard allowed | `onCreateInputConnection` → null | `FLAG_ALT_FOCUSABLE_IM` |
|---|---|---|---|
| KEYEVENT (`dispatchKeyEvent` + `KeyCharacterMap`) | 14/17 | 14/17 | 14/17 |
| **IC** (`InputConnection.commitText` / `sendKeyEvent`) | **17/17** | 3/17 | **17/17, system keyboard hidden** |
| BRIDGE (JS → Monaco/xterm APIs) | 17/17 | 17/17 | 17/17 |
| SYNTH (synthetic DOM `KeyboardEvent`) | 5/17 | — | — |

- **KEYEVENT** works for every key and shortcut, but non-ASCII letters (ą, ż, ó…) are lost. `ACTION_MULTIPLE` is ignored by Chromium.
- **Returning `null` from `onCreateInputConnection` doesn't block the system keyboard.** Chromium hands its connection to the IME through its own proxy view, so the keyboard still shows and we lose the connection. Don't use it.
- **`FLAG_ALT_FOCUSABLE_IM` + IC** gives everything: trusted events, Polish letters, shortcuts, and no system keyboard. This is the chosen design (decision table).
- **BRIDGE** always works but produces no DOM key events, so VS Code keybindings and extensions wouldn't see the keys. Keep it for app-level actions only (open preview, toggle panels).
- **SYNTH** (untrusted events) only triggers shortcuts. It never inserts text.

### Honor Magic V3 (2026-11-10): MagicOS 11 / Android 17, WebView 156

**Exec from the app's data dir works** with `targetSdk 28`, both directly and through `linker64`. But this Android installs only apps with `targetSdk ≥ 28` (`min_supported_target_sdk = 28`), so see risk 7 in §5.

**Fold detection works through Jetpack WindowManager.** No Honor SDK is needed.

| Posture | What MagicOS reports |
|---|---|
| Flat (inner screen) | `FLAT`, `VERTICAL`, hinge at x=1078 (exact middle of 2156 px), window 719×726 dp |
| Book (half-open, portrait) | `HALF_OPENED` + `VERTICAL`, `isSeparating=true` |
| Laptop (half-open, inner screen in landscape) | `HALF_OPENED` + `HORIZONTAL`, hinge at y=1078, window 748×664 dp. Reported ~0.3 s after rotating |
| Folded | no fold feature, window 353×737 dp on the outer screen. The hinge sensor reads FOLDED before the window even moves |

- **The Activity survived fold → outer screen → unfold without being recreated.** Only `onConfigurationChanged` ran and the log kept going. That's the basis for seamless fold/unfold with a live VS Code WebView.
- At ~170°+ MagicOS reports `FLAT`. During fast folding it flips BOOK↔FLAT a few times within a second, so posture changes need a debounce (~400 ms) before switching layouts.

**Keyboard spike:** identical to the emulator.

| Mode | KEYEVENT | IC | BRIDGE | SYNTH |
|---|---|---|---|---|
| System keyboard allowed | 14/17 | **17/17** | 17/17 | 5/17 |
| `FLAG_ALT_FOCUSABLE_IM` | 14/17 | **17/17** | 17/17 | 5/17 |

With `FLAG_ALT_FOCUSABLE_IM`, the system keyboard stayed hidden in all 68 checks. This confirms the chosen keyboard design on the real phone.

**Other facts**
- The phantom-process `device_config` keys are unset (system defaults), so phase 7 has to check the "Disable child process restrictions" developer option.
- Raw results: `out/phase0/<device>/` (git-ignored) (`device-report.txt`, `system.txt`, `kbd-spike-*.log`, `fold-probe.log`).

**Phase 0 is done.** Next is phase 1, the package toolchain.

---

## 9. Phase 1 findings

### Upstream termux-packages problems hit with a custom package name (2026-11-10)

All fixed by patches in `packages/patches/`:

| Patch | Problem | Effect without the fix |
|---|---|---|
| `0003` | `build-bootstraps.sh` still lists `bzip2`, which is now a subpackage of `libbz2` | Build stops |
| `0004` | `apr-util` 1.6.5 was removed from `downloads.apache.org` (a git build dependency) | Build stops. The fix uses `archive.apache.org`; same file, SHA256 checked |
| `0005` | termux-tools' `configure` reads `TERMUX_PREFIX`/`TERMUX_APP_PACKAGE` from the environment, but the build system doesn't export them | `pkg`, `login`, `chsh`, `termux-setup-storage`, motd and a profile.d script silently use `/data/data/com.termux`. Official Termux never notices, because there that's the right path |

`build-bootstraps.sh` has two more problems, so it's only used to compile now:
- It writes `TERMUX_PACKAGE_MANAGER=""` into the second-stage script, so package postinst scripts are skipped.
- It packs **every** built `.deb`, build-only dependencies included: 146 MB with python3, perl and subversion.

The zip itself comes from `generate-bootstraps.sh --repository <our repo>`, which takes only the listed packages and their runtime dependencies.

`scripts/check-prefix-leaks.sh` now fails the build if any script or binary in the bootstrap still contains `/data/data/com.termux`. It ignores comments, docs, headers and tests.

### First bootstrap on the Magic V3

This was the oversized build-bootstraps zip, before the fixes above. The slim bootstrap's results follow below.
- Install: 18,837 files and 2,922 symlinks extracted by `Runtime.installBootstrap`.
- Working: bash 5.3, apt 2.8.4, dpkg 1.22, git 2.56 (init and commit), ripgrep 15.2, ssh-keygen (ed25519), and `#!/usr/bin/env` scripts through termux-exec (`LD_PRELOAD=libtermux-exec-ld-preload.so`).
- `apt update` against the signed dev repo works through `adb reverse`, with InRelease verified by our key.
- Starting the second stage directly fails with "Permission denied", because the script isn't executable in the zip. It now runs through bash.

### Slim bootstrap on the Magic V3 (2026-11-10)

Built from the repo by `generate-bootstraps.sh`, leak-checked.

| | First zip | Slim zip |
|---|---|---|
| Size | 146 MB, 177 packages | **52 MB, 94 packages** |
| Install time | — | **7.6 s** (4,556 files, 1,446 symlinks) |
| Second stage | skipped postinst | **exit 0, postinst ran** |

- **Second stage details:** `update-alternatives` set up the pager and editor, openssh generated host keys, and termux-exec picked `libtermux-exec-direct-ld-preload.so` for API 37.
- **`apt install make sqlite` from the dev repo works:** sqlite 3.54.1 and make 4.4.1.
- **Everything that worked on the first zip still works:** bash 5.3, git 2.56, OpenSSH 10.6, ripgrep 15.2, shebangs and `apt update`. The phone has 96 packages after the install.
- **Runtime:** the second stage now runs through `bash` and exits 0.

### Node.js on the Magic V3 (2026-11-11)

Bootstrap with `nodejs-lts`: 85 MB, installs in 4.9 s (5,139 files). Node.js **24.22.2** built in about 39 minutes.

- `node` runs as `v24.22.2 android arm64`. A local HTTP server on 127.0.0.1 works, which is what code-server needs.
- **npm was missing.** In this termux-packages version npm is a separate `npm` package (12.2.2) that nodejs-lts only *recommends*, so the bootstrap left it out. It's now added to the bootstrap list. `corepack` (for pnpm/yarn) is part of nodejs-lts.
- **Patch `0006`:** `generate-bootstraps.sh` always took the first alternative of `a | b` dependencies. For npm (`nodejs | nodejs-lts`) that's `nodejs`, which isn't built. It now picks the first alternative that exists in the repo.
- **Open for phase 2:** check which Node major version code-server needs (22 or 24) before building it.
- **Second phone run (npm in the bootstrap):** npm was there, but running it gave "Permission denied". Its entry point `lib/node_modules/npm/bin/npm-cli.js` wasn't executable, because the installer, following the Termux app, only marked `bin/`, `libexec` and the apt helpers executable. The zip stores real Unix modes (Info-ZIP, "made by Unix"), so `ZipModes` now reads them from the central directory. About 200 executables live outside `bin/`: npm, corepack, git hook templates, the sshd service and more. A unit test (`ZipModesTest`) checks the parser against the real bootstrap.

### Node.js + npm on the Magic V3, final run (2026-11-11)

| Check | Result |
|---|---|
| Install | 89 MB zip, **5.4 s**; 7,080 files, of which **640 executable** (modes taken from the zip), 1,557 symlinks; second stage exit 0 |
| node | `v24.22.2 android arm64`; local HTTP server OK |
| npm | **12.2.2**; `npm install is-number@7` from registry.npmjs.org works and the module loads |
| Earlier checks | bash, git, OpenSSH, ripgrep, shebangs and `apt update` from the dev repo all still pass |

The core toolchain for phase 2 (Node + npm on bionic) is in place.

---

## 10. Phase 2 findings

### First VS Code run on the Magic V3 (2026-11-11)

- `apt install code-server` from the dev repo pulled in libandroid-spawn, glib, libsecret and python (a glib dependency) as well.
- `VsCodeProbeActivity` timings and checks:

| Step | Result |
|---|---|
| Backend `/healthz` | **1.1 s** after start |
| Automatic login (cookie) | OK |
| Workbench page loaded | **3.9 s** |
| Workbench check | `.monaco-workbench` and editor present, explorer shows `hello.js`, 7 status bar items, no error notifications |
| Extension host | launched (`Extension host agent started`, `Launched Extension Host Process`) |

- **Harmless log errors:**
  - "Unable to retrieve mac address" and `@vscode/deviceid` "Unsupported platform": Android hides both, VS Code falls back, and telemetry is off anyway.
  - `vsda` not found: normal for non-Microsoft builds.
  - All three can be silenced later.
- **Seen on the outer screen (screenshot in `out/phase2/`, git-ignored):**
  - It works, but the layout is cramped.
  - It starts in **Restricted Mode** (workspace trust).
  - The **Chat/agent panel** is open by default and takes half the width.
  - The light theme is on.
  - The Welcome page opens instead of the file.

### Next: default settings profile (phase 3/4)
- Turn off workspace trust for local projects (or pass `--disable-workspace-trust`).
- Hide the AI chat panel by default (`chat.disableAIFeatures` / secondary sidebar hidden); terminal CLI agents are unaffected.
- Default to a dark theme and skip the Welcome page.
- Touch-friendly sizes (§3).

---

## 11. Phase 3 findings

### Fresh install on the Magic V3 (2026-11-11)

Uninstalled FoldCode, installed the new APK, launched it. No PC was involved after the install (no adb reverse, no dev repo).

| Step | Result |
|---|---|
| Download + install + backend start | **43 s** from launch to `HTTP server listening`: 260 MB bootstrap from GitHub Releases, SHA-256 verified, installed |
| Workbench | Opens full screen on the outer screen with the `projects` folder; extension host launched |
| Theme | **Dark**, matching Android's dark mode (`window.autoDetectColorScheme` + WebView force-dark in web-theme-only mode) |
| AI chat panel | Hidden; the right side is the empty editor area |
| Restricted Mode / Welcome page | Neither shown |
| Background service | Running (`isForeground=true`) |
| Notification | **Not visible**: MagicOS set `POST_NOTIFICATIONS` to denied/importance NONE without prompting |

Notes:
- VS Code rewrote the theme names in `settings.json` (`Default Dark Modern` → `Dark Modern`), which confirms it read our defaults.
- On MagicOS, `Log.i` lines from the app don't show up in `adb logcat`. Diagnose from files (`files/logs/code-server.log`, the probes' logs) instead.
- Before publishing, the bootstrap on GitHub was checked from the PC: Range/206 resume, `InRelease` signature, `Packages` hash, and a byte-identical bootstrap (SHA-256 `1daa5a20…`).
