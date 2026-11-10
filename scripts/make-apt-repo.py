#!/usr/bin/env python3
"""Builds a signed flat-pool apt repository from .deb files (no dpkg/apt tools needed on the host).

    scripts/make-apt-repo.py [--debs out/debs] [--out out/repo]

Layout:  pool/<name>_<ver>_<arch>.deb
         dists/stable/main/binary-aarch64/Packages(.gz)
         dists/stable/{Release,InRelease,Release.gpg}
Signed with the key from scripts/repo-key.sh (keys/gnupg).
"""
import argparse
import email.utils
import gzip
import hashlib
import io
import os
import shutil
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ARCH = "aarch64"


def read_ar(data: bytes) -> dict:
    """Minimal `ar` reader (a .deb is an ar archive)."""
    assert data[:8] == b"!<arch>\n", "not an ar archive"
    pos, members = 8, {}
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        pos += 60
        members[name] = data[pos:pos + size]
        pos += size + (size & 1)
    return members


def control_of(deb: Path) -> str:
    members = read_ar(deb.read_bytes())
    name = next(n for n in members if n.startswith("control.tar"))
    with tarfile.open(fileobj=io.BytesIO(members[name]), mode="r:*") as tar:
        for m in tar.getmembers():
            if m.name.lstrip("./") == "control":
                return tar.extractfile(m).read().decode().strip()
    raise ValueError(f"{deb}: no control file")


def field(control: str, key: str) -> str:
    for line in control.splitlines():
        if line.startswith(key + ":"):
            return line.split(":", 1)[1].strip()
    return ""


def version_key(v: str):
    # Good enough to pick the newest build of the same package for a dev repo.
    return [int(p) if p.isdigit() else p for p in v.replace("-", ".").replace(":", ".").replace("+", ".").replace("~", ".").split(".")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--debs", default=str(ROOT / "out/debs"))
    ap.add_argument("--out", default=str(ROOT / "out/repo"))
    ap.add_argument("--gnupg", default=str(ROOT / "keys/gnupg"))
    args = ap.parse_args()

    out = Path(args.out)
    pool = out / "pool"
    binary = out / "dists/stable/main" / f"binary-{ARCH}"
    if out.exists():
        shutil.rmtree(out)
    pool.mkdir(parents=True)
    binary.mkdir(parents=True)

    newest = {}
    for deb in sorted(Path(args.debs).glob("*.deb")):
        control = control_of(deb)
        arch = field(control, "Architecture")
        if arch not in (ARCH, "all"):
            continue
        name, ver = field(control, "Package"), field(control, "Version")
        if name not in newest or version_key(ver) > version_key(newest[name][1]):
            newest[name] = (deb, ver, control)

    stanzas = []
    for name in sorted(newest):
        deb, ver, control = newest[name]
        target = pool / deb.name
        shutil.copy2(deb, target)
        blob = target.read_bytes()
        stanzas.append(
            control
            + f"\nFilename: pool/{deb.name}"
            + f"\nSize: {len(blob)}"
            + f"\nMD5sum: {hashlib.md5(blob).hexdigest()}"
            + f"\nSHA256: {hashlib.sha256(blob).hexdigest()}"
        )
    packages = ("\n\n".join(stanzas) + "\n").encode()
    (binary / "Packages").write_bytes(packages)
    with gzip.GzipFile(binary / "Packages.gz", "wb", mtime=0) as gz:
        gz.write(packages)

    dist = out / "dists/stable"
    lines = [
        "Origin: FoldCode",
        "Label: FoldCode",
        "Suite: stable",
        "Codename: stable",
        f"Date: {email.utils.formatdate(usegmt=True)}",
        f"Architectures: {ARCH}",
        "Components: main",
        "Description: FoldCode packages for the io.fold prefix",
    ]
    files = [p for p in sorted(dist.rglob("*")) if p.is_file()]
    for algo, fn in (("MD5Sum", hashlib.md5), ("SHA256", hashlib.sha256)):
        lines.append(f"{algo}:")
        for p in files:
            b = p.read_bytes()
            lines.append(f" {fn(b).hexdigest()} {len(b):>10} {p.relative_to(dist)}")
    (dist / "Release").write_text("\n".join(lines) + "\n")

    env = dict(os.environ, GNUPGHOME=args.gnupg)
    fpr = (ROOT / "keys/fingerprint").read_text().strip()
    subprocess.run(["gpg", "--batch", "--yes", "-u", fpr, "--clearsign", "-o", str(dist / "InRelease"), str(dist / "Release")], check=True, env=env)
    subprocess.run(["gpg", "--batch", "--yes", "-u", fpr, "--armor", "--detach-sign", "-o", str(dist / "Release.gpg"), str(dist / "Release")], check=True, env=env)
    print(f"repo: {len(newest)} packages → {out}")


if __name__ == "__main__":
    main()
