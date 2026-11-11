# FoldCode packages

Prebuilt packages for the FoldCode Android app: aarch64, prefix `/data/data/io.fold/files/usr`.

They're built from [termux-packages](https://github.com/termux/termux-packages) for FoldCode's own package name. They don't work in Termux, and Termux packages don't work in FoldCode.

## apt repository

The repository lives in the assets of the `stable` release:

```
deb https://github.com/KubeKoslaw/foldcode-packages/releases/download/stable/ ./
```

`InRelease` is signed with the key in [`foldcode-repo.asc`](foldcode-repo.asc). FoldCode sets up this source and its key by itself.

## Bootstrap

Releases named `bootstrap-<hash>` contain `bootstrap-aarch64.zip`. That's the base environment FoldCode downloads on first start: bash, coreutils, apt, git, OpenSSH, ripgrep, Node.js, npm and the VS Code backend (code-server). The app checks the zip's SHA-256 before installing it.

## Sources and licenses

Every package keeps its upstream license. The license texts are installed with each package under `share/doc/<package>/`.

The packages are built from termux-packages commit `@TERMUX_PACKAGES_COMMIT@`, plus the files in [`sources/`](sources):

- `sources/patches/`: changes applied to that termux-packages commit
- `sources/recipes/`: package recipes added by FoldCode (`code-server`)

Each package's `packages/<name>/build.sh` at that commit names the upstream source archive and its checksum.

To rebuild a package:

1. Check out the commit.
2. Apply the patches.
3. Copy the recipes into `packages/`.
4. Run `./scripts/run-docker.sh ./build-package.sh -a aarch64 <package>`.
