#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

"""Cache complete amd64 base images as immutable local OCI layouts."""

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
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BUILD = "m.daocloud.io/docker.io/library/maven:3.9.11-eclipse-temurin-25"
DEFAULT_RUNTIME = "m.daocloud.io/docker.io/library/eclipse-temurin:25-jre"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")


def canonical(image):
    return image.replace("docker.m.daocloud.io/", "m.daocloud.io/docker.io/", 1) if image.startswith("docker.m.daocloud.io/") else image


def sources(image):
    alternate = None
    if image.startswith("m.daocloud.io/docker.io/"):
        alternate = image.replace("m.daocloud.io/docker.io/", "docker.m.daocloud.io/", 1)
    elif image.startswith("docker.m.daocloud.io/"):
        alternate = canonical(image)
    return [image, alternate, image] if alternate else [image] * 3


def blob(layout, descriptor):
    digest = descriptor["digest"]
    if not DIGEST.fullmatch(digest):
        raise ValueError("Invalid OCI digest")
    path = layout / "blobs" / "sha256" / digest[7:]
    if path.is_symlink():
        raise ValueError("Unexpected OCI blob symlink")
    check = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            check.update(chunk)
    if check.hexdigest() != digest[7:] or path.stat().st_size != descriptor["size"]:
        raise ValueError(f"Corrupt OCI blob: {digest}")
    return path


def verify(layout, expected=None):
    if json.loads((layout / "oci-layout").read_text())["imageLayoutVersion"] != "1.0.0":
        raise ValueError("Unsupported OCI layout version")
    manifests = json.loads((layout / "index.json").read_text())["manifests"]
    if len(manifests) != 1:
        raise ValueError("Expected exactly one base image manifest")
    descriptor = manifests[0]
    if expected and descriptor["digest"] != expected:
        raise ValueError("Cached manifest does not match the selected digest")
    manifest = json.loads(blob(layout, descriptor).read_text())
    config = json.loads(blob(layout, manifest["config"]).read_text())
    if config["architecture"] != "amd64" or config["os"] != "linux":
        raise ValueError("Base image must be linux/amd64")
    for layer in manifest["layers"]:
        blob(layout, layer)
    return descriptor["digest"]


def result(role, image, source, digest, layout):
    return {"image": image, "source": source, "digest": digest,
            "context": f"oci-layout://{layout}@{digest}"}


def prepare(role, image, cache, refresh=False):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:@-]+", image):
        raise ValueError("Supply a complete image reference without whitespace")
    identity = canonical(image)
    fingerprint = hashlib.sha256(f"oci-base-v1\nlinux/amd64\n{identity}".encode()).hexdigest()
    entry = cache / fingerprint
    entry.mkdir(parents=True, exist_ok=True)
    selected = entry / "selected.json"
    if selected.exists() and not refresh:
        try:
            metadata = json.loads(selected.read_text())
            if metadata["image"] != identity or not DIGEST.fullmatch(metadata["digest"]):
                raise ValueError("Invalid base image cache metadata")
            layout = entry / metadata["digest"][7:]
            verify(layout, metadata["digest"])
            print(f"Base image cache HIT ({role}): {image}", file=sys.stderr)
            return result(role, image, metadata["source"], metadata["digest"], layout)
        except (OSError, ValueError, KeyError, TypeError) as error:
            print(f"Rebuilding invalid base image cache ({role}): {error}", file=sys.stderr)
    print(f"Preparing base image cache ({role}): {image}", file=sys.stderr)
    for attempt, source in enumerate(sources(image), 1):
        with tempfile.TemporaryDirectory(prefix="download-", dir=entry) as temporary:
            temporary = Path(temporary)
            context = temporary / "context"
            context.mkdir()
            (context / "Dockerfile").write_text(f"FROM {source}\n")
            layout = temporary / "layout"
            print(f"Base image download {attempt}/3: {source}", file=sys.stderr)
            try:
                subprocess.run(["docker", "buildx", "build", "--platform", "linux/amd64", "--provenance=false",
                                "--progress", "plain", "--output", f"type=oci,dest={layout},tar=false", str(context)],
                               stdout=sys.stderr, stderr=sys.stderr, check=True, timeout=300)
                digest = verify(layout)
                destination = entry / digest[7:]
                if destination.exists():
                    try:
                        verify(destination, digest)
                    except (OSError, ValueError, KeyError, TypeError):
                        shutil.rmtree(destination)
                if not destination.exists():
                    layout.rename(destination)
                metadata = {"image": identity, "source": source, "digest": digest}
                pending = temporary / "selected.json"
                pending.write_text(json.dumps(metadata, ensure_ascii=False) + "\n")
                os.replace(pending, selected)
                return result(role, image, source, digest, destination)
            except (subprocess.SubprocessError, OSError, ValueError, KeyError, TypeError) as error:
                print(f"Base image attempt {attempt} failed: {error}", file=sys.stderr)
        if attempt < 3:
            time.sleep(2)
    raise RuntimeError(f"Unable to cache {image}; an existing valid cache has not been removed. "
                       "Retry when the mirror recovers or override BUILD_BASE_IMAGE / RUNTIME_BASE_IMAGE.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-image", default=os.environ.get("BUILD_BASE_IMAGE") or DEFAULT_BUILD)
    parser.add_argument("--runtime-image", default=os.environ.get("RUNTIME_BASE_IMAGE") or DEFAULT_RUNTIME)
    parser.add_argument("--refresh", action="store_true", help="Refresh selected images; keep previous immutable layouts")
    args = parser.parse_args()
    cache = ROOT / ".dev" / "base-images"
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        images = {role: prepare(role, image, cache, args.refresh)
                  for role, image in [("build", args.build_image), ("runtime", args.runtime_image)]}
    print(json.dumps(images, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        sys.exit(str(error))
