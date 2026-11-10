#!/usr/bin/env bash
# Creates (once) the GPG key that signs the FoldCode apt repo, in its own keyring under keys/
# (never your personal ~/.gnupg), and exports the public key into the app's assets so the app
# can install it as $PREFIX/etc/apt/trusted.gpg.d/foldcode.gpg.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
export GNUPGHOME="$root/keys/gnupg"
uid="FoldCode packages <packages@foldcode.invalid>"

mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
if ! gpg --batch --list-secret-keys "$uid" >/dev/null 2>&1; then
    gpg --batch --passphrase '' --quick-gen-key "$uid" ed25519 sign never
fi
fpr="$(gpg --batch --with-colons --list-secret-keys "$uid" | awk -F: '/^fpr:/ {print $10; exit}')"
mkdir -p "$root/app/src/main/assets"
gpg --batch --export "$fpr" > "$root/app/src/main/assets/foldcode-repo.gpg"
echo "$fpr" > "$root/keys/fingerprint"
echo "repo key $fpr → app/src/main/assets/foldcode-repo.gpg"
