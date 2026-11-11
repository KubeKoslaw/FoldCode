#!/usr/bin/env bash
# Publishes the built packages to the public GitHub repo KubeKoslaw/foldcode-packages:
#  - the repo itself only holds README, the public repo key and the build sources (patches, recipes),
#  - release "stable": the flat apt repository (deb <url> ./),
#  - release "bootstrap-<sha256 prefix>": bootstrap-aarch64.zip,
# and pins the bootstrap URL + SHA-256 for the app in app/bootstrap.properties.
# Commits are authored only by the repo owner (noreply address) and pass the privacy hooks.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
OWNER="KubeKoslaw"
REPO="foldcode-packages"
FULL="$OWNER/$REPO"
NOREPLY="324421697+KubeKoslaw@users.noreply.github.com"
work="$root/out/publish/$REPO"
flat="$root/out/repo-flat"
bootstrap="$root/out/bootstrap/bootstrap-aarch64.zip"
tp_commit="$(git -C "$root/third_party/termux-packages" rev-parse HEAD)"

echo "== flat apt repo"
python3 -I "$root/scripts/make-apt-repo.py" --flat --out "$flat"

echo "== public repo contents"
if ! gh repo view "$FULL" >/dev/null 2>&1; then
    gh repo create "$FULL" --public --description "Prebuilt packages for the FoldCode Android app (io.fold prefix, aarch64)"
fi
if [ ! -d "$work/.git" ]; then
    mkdir -p "$work"
    git -C "$work" init -q -b main
    git -C "$work" remote add origin "https://github.com/$FULL.git"
fi
git -C "$work" config user.name "$OWNER"
git -C "$work" config user.email "$NOREPLY"
git -C "$work" config commit.gpgsign false
git -C "$work" config core.hooksPath .githooks

mkdir -p "$work/sources"
rm -rf "$work/sources/patches" "$work/sources/recipes" "$work/.githooks"
cp -r "$root/packages/patches" "$work/sources/patches"
cp -r "$root/packages/recipes" "$work/sources/recipes"
cp -r "$root/.githooks" "$work/.githooks"
echo "$tp_commit" > "$work/sources/TERMUX_PACKAGES_COMMIT"
sed "s/@TERMUX_PACKAGES_COMMIT@/$tp_commit/" "$root/packages/publish/README.md" > "$work/README.md"
GNUPGHOME="$root/keys/gnupg" gpg --batch --armor --export "$(cat "$root/keys/fingerprint")" > "$work/foldcode-repo.asc"

git -C "$work" add -A
if ! git -C "$work" diff --cached --quiet; then
    git -C "$work" commit -q -m "Update README, key and build sources"
fi
git -C "$work" push -q -u origin main

echo "== release: stable (apt repo)"
gh release view stable -R "$FULL" >/dev/null 2>&1 || \
    gh release create stable -R "$FULL" --target main --title "apt repository" \
        --notes "Flat apt repository for FoldCode. Source line: deb https://github.com/$FULL/releases/download/stable/ ./"
gh release upload stable -R "$FULL" --clobber "$flat"/*
# Remove assets that are no longer part of the repo.
comm -13 <(ls "$flat" | sort) <(gh release view stable -R "$FULL" --json assets --jq '.assets[].name' | sort) | while read -r stale; do
    gh release delete-asset stable "$stale" -R "$FULL" -y
done

echo "== release: bootstrap"
sha="$(sha256sum "$bootstrap" | cut -d' ' -f1)"
tag="bootstrap-${sha:0:12}"
gh release view "$tag" -R "$FULL" >/dev/null 2>&1 || \
    gh release create "$tag" -R "$FULL" --target main --title "Bootstrap ${sha:0:12}" \
        --notes "Base environment for FoldCode. SHA-256: $sha" "$bootstrap"

cat > "$root/app/bootstrap.properties" <<EOF
# Written by scripts/publish-packages.sh: what the app downloads and verifies on first start.
bootstrap.url=https://github.com/$FULL/releases/download/$tag/bootstrap-aarch64.zip
bootstrap.sha256=$sha
bootstrap.size=$(stat -c %s "$bootstrap")
repo.url=https://github.com/$FULL/releases/download/stable/
EOF
echo "== done: $tag, $(ls "$flat" | wc -l) repo files"
