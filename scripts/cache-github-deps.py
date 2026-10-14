#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

"""Prepare a portable Yarn 1 offline mirror for exactly the GitHub lockfile entries."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def github_entries(lockfile):
    entries = []
    for block in re.split(r"\n(?=\S)", lockfile.read_text()):
        resolved = re.search(r'^  resolved "([^"\n]+)"$', block, re.M)
        if not resolved or not resolved[1].startswith("https://github.com/"):
            continue
        selectors = re.findall(r'"([^"\n]+)"', block.splitlines()[0])
        names = {selector.split("@https://github.com/", 1)[0] for selector in selectors}
        version = re.search(r'^  version "([^"\n]+)"$', block, re.M)
        if len(names) != 1 or not version:
            raise ValueError("Unsupported GitHub lockfile entry")
        reference, digest = resolved[1].rsplit("#", 1)
        if not re.fullmatch(r"[0-9a-f]{40}", digest):
            raise ValueError("GitHub dependencies must have a locked commit or SHA-1")
        url = urlsplit(reference)
        if url.username or url.password or url.query:
            raise ValueError("Only public GitHub dependency URLs are supported")
        kind = "git" if url.path.endswith(".git") else "tarball"
        filename = Path(url.path).name + ("-" + digest if kind == "git" else "")
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+", filename):
            raise ValueError("Unsupported offline mirror filename")
        entries.append({"name": names.pop(), "version": version[1], "resolved": resolved[1],
                        "reference": reference, "digest": digest, "kind": kind, "filename": filename})
    if not entries:
        raise ValueError("No locked GitHub dependencies found")
    filenames = [entry["filename"] for entry in entries]
    if len(set(filenames)) != len(filenames):
        raise ValueError("GitHub tarball filenames collide in Yarn's offline mirror")
    return sorted(entries, key=lambda entry: entry["name"])


def checksum(path, algorithm="sha256"):
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_archive(path, entry):
    if entry["kind"] == "tarball" and checksum(path, "sha1") != entry["digest"]:
        raise ValueError(f"Locked SHA-1 mismatch: {entry['name']}")
    with tarfile.open(path) as archive:
        manifests = [item for item in archive if item.name.lstrip("./") == "package.json"
                     or (item.name.endswith("/package.json") and item.name.count("/") == 1)]
        if len(manifests) != 1:
            raise ValueError(f"Missing root package manifest: {entry['name']}")
        manifest = json.load(archive.extractfile(manifests[0]))
        if manifest.get("name") != entry["name"] or manifest.get("version") != entry["version"]:
            raise ValueError(f"Cached package name/version mismatch: {entry['name']}")
        if entry["kind"] == "git" and manifest.get("scripts", {}).get("prepare"):
            raise ValueError(f"Git prepare scripts require a prepared Yarn cache: {entry['name']}")


def yarn_cache_dirs(explicit):
    roots = []
    if explicit:
        roots.append(Path(explicit).expanduser())
    if os.environ.get("YARN_CACHE_FOLDER"):
        roots.append(Path(os.environ["YARN_CACHE_FOLDER"]).expanduser())
    node = ROOT / "ui-ngx/target/node/node"
    yarn = ROOT / "ui-ngx/target/node/yarn/dist/bin/yarn.js"
    if node.is_file() and yarn.is_file():
        result = subprocess.run([str(node), str(yarn), "cache", "dir"], cwd=ROOT / "ui-ngx",
                                capture_output=True, text=True, timeout=15)
        if result.returncode == 0 and result.stdout.strip():
            roots.append(Path(result.stdout.strip().splitlines()[-1]))
    roots.extend([Path.home() / "Library/Caches/Yarn", Path.home() / ".cache/yarn",
                  Path("/usr/local/share/.cache/yarn")])
    return list(dict.fromkeys(path.resolve() for root in roots for path in (root, root / "v6") if path.is_dir()))


def from_yarn_cache(entry, roots):
    normalized = re.sub(r"[^@a-z0-9]+", "-", entry["name"]).strip("-")
    directory = f"npm-{normalized}-{entry['version']}-{entry['digest']}"
    for root in roots:
        package = root / directory / "node_modules" / entry["name"]
        metadata = package / ".yarn-metadata.json"
        archive = package / ".yarn-tarball.tgz"
        if not metadata.is_file() or not archive.is_file():
            continue
        try:
            info = json.loads(metadata.read_text())
            if info.get("remote", {}).get("resolved") != entry["resolved"]:
                continue
            validate_archive(archive, entry)
            return archive
        except (ValueError, OSError, tarfile.TarError):
            continue
    return None


def download(entry, destination):
    source = entry["reference"]
    if entry["kind"] == "git":
        repository = urlsplit(source).path.removesuffix(".git").lstrip("/")
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", repository):
            raise ValueError("Unsupported GitHub repository")
        source = f"https://codeload.github.com/{repository}/tar.gz/{entry['digest']}"
    with tempfile.TemporaryDirectory(dir=destination.parent) as directory:
        downloaded = Path(directory) / "download.tar.gz"
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--retry-delay", "2",
                        "--connect-timeout", "15", "--max-time", "120", "--silent", "--show-error",
                        "--output", str(downloaded), source], check=True)
        if entry["kind"] == "tarball":
            shutil.copyfile(downloaded, destination)
        else:
            # Yarn's GitFetcher expects a git-archive layout without GitHub's outer directory.
            with tarfile.open(downloaded) as original, tarfile.open(destination, "w") as converted:
                members = original.getmembers()
                prefix = members[0].name.split("/", 1)[0] + "/"
                for member in members:
                    if member.name.rstrip("/") == prefix.rstrip("/"):
                        continue
                    if not member.name.startswith(prefix):
                        raise ValueError("Unexpected GitHub archive root")
                    relative = member.name[len(prefix):]
                    if not relative:
                        continue
                    if relative.startswith("/") or ".." in Path(relative).parts:
                        raise ValueError("Unsafe GitHub archive path")
                    content = original.extractfile(member) if member.isfile() else None
                    member.name = relative
                    converted.addfile(member, content)
    validate_archive(destination, entry)


def prepare(cache_root, entries, roots):
    identity = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()[:24]
    directory = cache_root / identity
    archives = directory / "archives"
    archives.mkdir(parents=True, exist_ok=True)
    with (directory / ".lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        manifest_path = directory / "manifest.json"
        previous = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
        manifest = dict(previous)
        for entry in entries:
            name = entry["name"]
            artifact = archives / entry["filename"]
            old = previous.get(name, {})
            if artifact.is_file() and old.get("resolved") == entry["resolved"] and old.get("sha256") == checksum(artifact):
                print(f"GitHub cache HIT: {name}@{entry['version']}", file=sys.stderr)
                manifest[name] = old
                continue
            cached = from_yarn_cache(entry, roots)
            with tempfile.NamedTemporaryFile(dir=archives, delete=False) as stream:
                temporary = Path(stream.name)
            try:
                if cached:
                    print(f"GitHub cache SEED from Yarn: {name}@{entry['version']}", file=sys.stderr)
                    shutil.copyfile(cached, temporary)
                else:
                    print(f"GitHub cache DOWNLOAD locked revision: {name}@{entry['version']}", file=sys.stderr)
                    download(entry, temporary)
                validate_archive(temporary, entry)
                temporary.replace(artifact)
            finally:
                temporary.unlink(missing_ok=True)
            manifest[name] = {"version": entry["version"], "resolved": entry["resolved"],
                              "filename": entry["filename"], "sha256": checksum(artifact)}
            # Persist each successful package even if a subsequent download fails.
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        (directory / "checksums.sha256").write_text("".join(
            f"{value['sha256']}  archives/{value['filename']}\n" for value in manifest.values()))
    return directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".dev/github-deps")
    parser.add_argument("--yarn-cache", help="Optional existing Yarn cache to seed from")
    args = parser.parse_args()
    try:
        directory = prepare(args.cache_dir.resolve(), github_entries(ROOT / "ui-ngx/yarn.lock"), yarn_cache_dirs(args.yarn_cache))
        print(directory)
    except (ValueError, OSError, tarfile.TarError, subprocess.SubprocessError) as error:
        print(f"GitHub dependency cache failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
