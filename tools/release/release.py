#!/usr/bin/env python3
"""Cut a signed sideload release of The Box and publish it on GitHub (docs/design/m5-setup-settings.md §3.12).

Run from anywhere with `python3 -I tools/release/release.py [--dry-run] --notes "..."`. Stdlib only. See README.md.

Steps: preflight (clean tree, on main, main pushed, version strictly newer) -> version bump -> `:app:assembleRelease`
-> per-ABI + universal APKs checked with aapt2 (package, versionCode, minSdk) and apksigner (one signer, the release
certificate) -> `update.json` in the app's `UpdateManifest` schema -> `update.json.sig` (Ed25519, OpenSSL) -> the
signature verified against the keys compiled into the app (`ReleaseUpdateConfig`) -> commit, tag, push -> a draft
GitHub release with every asset, then published, then the public `latest/download` URLs re-checked.

`--dry-run` does everything up to and including the signature check into build/release/, with no file in the tree
changed (the version goes to Gradle as -P properties) and no git or network writes.

The GitHub token never reaches stdout, a log or an exception message.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(REPO_ROOT, "build", "release")
VERSION_FILE = os.path.join(REPO_ROOT, "version.properties")
ENDPOINTS_KT = os.path.join(REPO_ROOT, "app", "src", "main", "kotlin", "app", "thebox", "update", "UpdateEndpoints.kt")
POLICY_KT = os.path.join(REPO_ROOT, "core", "src", "main", "kotlin", "app", "thebox", "core", "update", "UpdatePolicy.kt")
APK_DIR = os.path.join(REPO_ROOT, "app", "build", "outputs", "apk", "release")

PACKAGE_NAME = "app.thebox"
SCHEMA = 1
CHANNEL = "stable"
# The release keystore's certificate (~/.thebox-release/release.jks, alias thebox): upper-case hex without colons, the
# form `UpdateManifest.signingCertSha256` documents. Changing it means every installed copy can no longer update.
RELEASE_CERT_SHA256 = "22409319E01C49126CE4C46207F1EB505E2D2BFC469E01A7C0EA0417E33C4634"
# APK splits (app/build.gradle.kts `splits.abi`) -> the manifest's `abi` and the published asset name. The names do not
# carry the version, so `releases/latest/download/TheBox-universal.apk` is a permanent sideload link (Downloader).
ABIS = {"armeabi-v7a": "armeabi-v7a", "arm64-v8a": "arm64-v8a", "universal": "universal"}
ASSET_PREFIX = "TheBox-"
MANIFEST_NAME = "update.json"
SIGNATURE_NAME = "update.json.sig"
DEFAULT_KEY_DIR = os.path.expanduser("~/.thebox-release")
DEFAULT_SIGNING_KEY = os.path.join(DEFAULT_KEY_DIR, "update-current.pem")
# Owner's paths that stay modified/untracked on the release Mac and do not block a release (CLAUDE.md, task brief).
IGNORED_DIRTY = ("gradle.properties", "gradle/gradle-daemon-jvm.properties", "design/nfl/support.js", "design/nfl/vendor/")
COMMIT_AUTHOR = ("Shawn Johnson", "38891628+shane9b3@users.noreply.github.com")
COMMIT_TRAILER = "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
DEFAULT_JAVA_HOME = "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
VERSION_NAME_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


class ReleaseError(Exception):
    pass


def log(msg):
    print(msg, flush=True)


# ------------------------------------------------------------------ pure parts (unit-tested in test_release.py)

def sha256_hex(data):
    """Upper-case hex SHA-256, the manifest's form (`Sha256Counter`, `ManifestVerifier.normaliseHex`)."""
    return hashlib.sha256(data).hexdigest().upper()


def normalise_hex(s):
    """`ManifestVerifier.normaliseHex`: upper case, no colons or whitespace."""
    return "".join(c for c in s if c != ":" and not c.isspace()).upper()


def sequence_for(version_code):
    """The manifest `sequence`. It is the versionCode itself: that already strictly increases with every release (Android
    refuses a lower one, and this script refuses to publish one that is not higher than every earlier tag and the live
    manifest), it needs no trusted clock on the release Mac, and it is reproducible from the tag. The client keeps the
    highest sequence it has accepted and refuses a lower one (a replayed old manifest cannot re-offer a withdrawn
    release); an equal one is the daily re-check of the same manifest. A fix to a published manifest is therefore a new
    release (N+1), which is also how §3.12.5 rolls back."""
    if not isinstance(version_code, int) or version_code < 1:
        raise ReleaseError(f"versionCode must be a positive integer, got {version_code!r}")
    return version_code


def check_newer(version_code, previous_codes, previous_sequence=None):
    """Refuses a versionCode (and so a sequence) that is not strictly above every earlier release."""
    prev = max(previous_codes, default=0)
    if version_code <= prev:
        raise ReleaseError(f"versionCode {version_code} is not above the highest released versionCode {prev}")
    if previous_sequence is not None and sequence_for(version_code) <= previous_sequence:
        raise ReleaseError(f"sequence {sequence_for(version_code)} is not above the live manifest's {previous_sequence}")


def bump_version_name(name):
    m = VERSION_NAME_RE.match(name)
    if not m:
        raise ReleaseError(f"versionName {name!r} is not MAJOR.MINOR.PATCH; pass --version-name")
    return f"{m.group(1)}.{m.group(2)}.{int(m.group(3)) + 1}"


def tag_for(version_name):
    if not VERSION_NAME_RE.match(version_name):
        raise ReleaseError(f"versionName {version_name!r} is not MAJOR.MINOR.PATCH")
    return "v" + version_name


def asset_name(abi):
    return f"{ASSET_PREFIX}{abi}.apk"


def asset_url(repo, tag, name):
    return f"https://github.com/{repo}/releases/download/{tag}/{urllib.parse.quote(name)}"


def manifest_url(repo):
    """Must equal `ReleaseUpdateConfig.manifestUrl` (checked against the Kotlin source in preflight)."""
    return f"https://github.com/{repo}/releases/latest/download/{MANIFEST_NAME}"


def build_manifest(*, version_code, version_name, min_sdk, apks, repo, tag, notes, published_at, withdrawn=(),
                   rollout_percent=100, cert_sha256=RELEASE_CERT_SHA256, package_name=PACKAGE_NAME):
    """The `UpdateManifest` (core/.../update/UpdateContracts.kt) as a dict. [apks] maps abi -> (asset name, bytes or
    (size, sha256))."""
    if not 0 <= rollout_percent <= 100:
        raise ReleaseError("rolloutPercent must be 0-100")
    entries = []
    for abi in ABIS:
        if abi not in apks:
            continue
        name, content = apks[abi]
        size, digest = (len(content), sha256_hex(content)) if isinstance(content, (bytes, bytearray)) else content
        if size <= 0 or len(normalise_hex(digest)) != 64:
            raise ReleaseError(f"APK {name}: bad size or SHA-256")
        entries.append({"abi": abi, "url": asset_url(repo, tag, name), "sizeBytes": size, "sha256": normalise_hex(digest)})
    if not any(e["abi"] == "universal" for e in entries):
        raise ReleaseError("no universal APK: the client falls back to it when no ABI matches")
    cert = normalise_hex(cert_sha256)
    if len(cert) != 64:
        raise ReleaseError("certificate digest must be 32 bytes of hex")
    return {
        "schema": SCHEMA,
        "sequence": sequence_for(version_code),
        "packageName": package_name,
        "channel": CHANNEL,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": min_sdk,
        "publishedAt": published_at,
        "notes": notes,
        "apks": entries,
        "signingCertSha256": [cert],
        "withdrawn": sorted({int(w) for w in withdrawn}),
        "rolloutPercent": rollout_percent,
    }


def manifest_bytes(manifest):
    """The exact bytes that are signed and published. Stable formatting, UTF-8, trailing newline."""
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def load_ed25519():
    """The fake panel's pure-Python RFC 8032 module (tools/fakepanel/ed25519.py), loaded by path (`python3 -I` puts no
    script directory on sys.path). Used here for verification only; signing with the real key goes through OpenSSL."""
    path = os.path.join(REPO_ROOT, "tools", "fakepanel", "ed25519.py")
    spec = importlib.util.spec_from_file_location("thebox_ed25519", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def verify_signature(data, signature, public_keys_hex):
    """Mirror of `ManifestVerifier` step 1: a 64-byte signature that one of the app's keys verifies. Returns the index."""
    if len(signature) != 64:
        raise ReleaseError(f"signature is {len(signature)} bytes, not 64")
    ed = load_ed25519()
    for i, k in enumerate(public_keys_hex):
        if ed.verify(bytes.fromhex(k), data, signature):
            return i
    raise ReleaseError("no key compiled into the app verifies update.json.sig")


def read_text(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def app_update_config(endpoints_kt=ENDPOINTS_KT, src=None):
    """`UPDATE_REPO` and `UPDATE_PUBLIC_KEYS_HEX` from the Kotlin source, so the manifest is checked against exactly what
    the release build trusts and fetches."""
    src = read_text(endpoints_kt) if src is None else src
    repo = re.search(r'val UPDATE_REPO: String\? = "([^"]+)"', src)
    if not repo:
        raise ReleaseError("ReleaseUpdateConfig.UPDATE_REPO is not set: the release updater would be disabled")
    keys_block = re.search(r"val UPDATE_PUBLIC_KEYS_HEX: List<String> = listOf\((.*?)\n\s*\)", src, re.S)
    keys = re.findall(r'"([0-9a-fA-F]{64})"', keys_block.group(1)) if keys_block else []
    if not keys:
        raise ReleaseError("ReleaseUpdateConfig.UPDATE_PUBLIC_KEYS_HEX is empty")
    url_fn = re.search(r'fun manifestUrl\(repo: String\): String = "([^"]+)"', src)
    if not url_fn or url_fn.group(1).replace("$repo", repo.group(1)) != manifest_url(repo.group(1)):
        raise ReleaseError("ReleaseUpdateConfig.manifestUrl no longer matches the URL this script publishes to")
    return repo.group(1), [k.lower() for k in keys]


def redirect_hosts(policy_kt=POLICY_KT):
    src = read_text(policy_kt)
    m = re.search(r"REDIRECT_HOSTS: Set<String> = setOf\((.*?)\)", src, re.S)
    if not m:
        raise ReleaseError("UpdateTimings.REDIRECT_HOSTS not found")
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def read_properties(text):
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def write_version(path, code, name):
    text = read_text(path)
    text, n1 = re.subn(r"(?m)^versionCode=.*$", f"versionCode={code}", text)
    text, n2 = re.subn(r"(?m)^versionName=.*$", f"versionName={name}", text)
    if n1 != 1 or n2 != 1:
        raise ReleaseError("version.properties needs exactly one versionCode= and one versionName= line")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def parse_badging(text):
    pkg = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    if not pkg or not sdk:
        raise ReleaseError("aapt2 badging without package or sdkVersion")
    return {"package": pkg.group(1), "versionCode": int(pkg.group(2)), "versionName": pkg.group(3), "minSdk": int(sdk.group(1))}


def parse_signers(text):
    """SHA-256 digests from `apksigner verify --print-certs` (one per signer)."""
    return [normalise_hex(d) for d in re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$", text, re.M)]


# ------------------------------------------------------------------ process helpers

def run(cmd, *, cwd=REPO_ROOT, env=None, check=True, capture=True, input_text=None):
    p = subprocess.run(cmd, cwd=cwd, env=env, text=True, input=input_text,
                       stdout=subprocess.PIPE if capture else None, stderr=subprocess.PIPE if capture else None)
    if check and p.returncode != 0:
        detail = (p.stderr or p.stdout or "").strip()[-2000:] if capture else ""
        raise ReleaseError(f"{cmd[0]} {' '.join(cmd[1:3])}... failed ({p.returncode}) {detail}")
    return p


def git(*args, check=True):
    return run(["git", *args], check=check).stdout.strip()


def build_env():
    env = dict(os.environ)
    env.setdefault("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    if not env.get("JAVA_HOME") and os.path.isdir(DEFAULT_JAVA_HOME):
        env["JAVA_HOME"] = DEFAULT_JAVA_HOME
    return env


def build_tool(env, name):
    root = os.path.join(env["ANDROID_HOME"], "build-tools")
    if not os.path.isdir(root):
        raise ReleaseError(f"no Android build-tools under {root}")

    def key(v):
        return [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", v)]

    for v in sorted(os.listdir(root), key=key, reverse=True):
        p = os.path.join(root, v, name)
        if os.path.isfile(p):
            return p
    raise ReleaseError(f"{name} not found in {root}")


# ------------------------------------------------------------------ GitHub

class GitHub:
    API = "https://api.github.com"
    UPLOADS = "https://uploads.github.com"

    def __init__(self, repo, token):
        self.repo = repo
        self._token = token  # never printed

    def __repr__(self):
        return f"GitHub({self.repo})"

    def request(self, method, url, body=None, content_type="application/json"):
        data = json.dumps(body).encode() if isinstance(body, dict) else body
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self._token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "thebox-release")
        if data is not None:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=600) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            msg = e.read().decode("utf-8", "replace")[:500]
            raise ReleaseError(f"GitHub {method} {urllib.parse.urlsplit(url).path}: HTTP {e.code} {msg}") from None

    def create_draft(self, tag, name, body):
        return self.request("POST", f"{self.API}/repos/{self.repo}/releases",
                            {"tag_name": tag, "name": name, "body": body, "draft": True, "prerelease": False, "make_latest": "true"})

    def upload(self, release, path, name, content_type):
        url = f"{self.UPLOADS}/repos/{self.repo}/releases/{release['id']}/assets?name={urllib.parse.quote(name)}"
        with open(path, "rb") as fh:
            return self.request("POST", url, fh.read(), content_type)

    def publish(self, release):
        return self.request("PATCH", f"{self.API}/repos/{self.repo}/releases/{release['id']}", {"draft": False, "make_latest": "true"})


def github_token():
    """GITHUB_TOKEN, else `gh auth token`, else `git credential fill` for github.com. Never echoed."""
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok
    if shutil.which("gh"):
        p = subprocess.run(["gh", "auth", "token"], text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        if p.returncode == 0 and p.stdout.strip():
            return p.stdout.strip()
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    p = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env, cwd=REPO_ROOT)
    if p.returncode == 0:
        for line in p.stdout.splitlines():
            if line.startswith("password="):
                return line[len("password="):].strip()
    raise ReleaseError("no GitHub token: set GITHUB_TOKEN, run `gh auth login`, or store a github.com credential in git")


def fetch_public(url, allowed_hosts, max_bytes=None):
    """GET without credentials, following redirects by hand only to [allowed_hosts] over https (as `UpdaterImpl` does).
    Returns (bytes, hosts visited) or (None, hosts) on 404."""

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    hosts = []
    for _ in range(10):
        parts = urllib.parse.urlsplit(url)
        hosts.append(parts.hostname)
        if parts.scheme != "https" or parts.hostname not in allowed_hosts:
            raise ReleaseError(f"redirect to {parts.scheme}://{parts.hostname} is outside the app's allowlist {sorted(allowed_hosts)}")
        req = urllib.request.Request(url, headers={"User-Agent": "TheBox/release-check"})
        try:
            with opener.open(req, timeout=120) as resp:
                return resp.read(max_bytes) if max_bytes else resp.read(), hosts
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308) and e.headers.get("Location"):
                url = urllib.parse.urljoin(url, e.headers["Location"])
                continue
            if e.code == 404:
                return None, hosts
            raise ReleaseError(f"GET {parts.hostname}{parts.path}: HTTP {e.code}") from None
    raise ReleaseError("too many redirects")


# ------------------------------------------------------------------ release steps

def dirty_paths():
    out = run(["git", "status", "--porcelain", "--untracked-files=all"]).stdout
    paths = []
    for line in out.splitlines():
        path = line[3:].split(" -> ")[-1].strip('"')
        if not any(path == p or (p.endswith("/") and path.startswith(p)) for p in IGNORED_DIRTY):
            paths.append(path)
    return paths


def preflight_git(dry_run):
    problems = []
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch != "main":
        problems.append(f"on branch {branch}, not main")
    dirty = dirty_paths()
    if dirty:
        problems.append("uncommitted changes: " + ", ".join(dirty[:10]) + (" ..." if len(dirty) > 10 else ""))
    head = git("rev-parse", "HEAD")
    remote = run(["git", "ls-remote", "origin", "refs/heads/main"], check=False)
    remote_head = remote.stdout.split()[0] if remote.returncode == 0 and remote.stdout.strip() else None
    if remote.returncode != 0:
        problems.append("cannot reach origin (offline, or no access)")
    elif remote_head is None:
        problems.append("origin has no main branch yet: push main first (git push -u origin main)")
    elif remote_head != head:
        problems.append(f"main is not pushed: HEAD {head[:9]}, origin/main {remote_head[:9]}")
    for p in problems:
        log(("  warning (dry run): " if dry_run else "  error: ") + p)
    if problems and not dry_run:
        raise ReleaseError("preflight failed; fix the above first")


def latest_tag():
    tags = git("tag", "-l", "v*", "--sort=-v:refname").split()
    return tags[0] if tags else None


def released_codes():
    """versionCodes of every v* tag (from that tag's version.properties; tags before version.properties count as 1)."""
    codes = {}
    for tag in git("tag", "-l", "v*").split():
        p = run(["git", "show", f"{tag}:version.properties"], check=False)
        codes[tag] = int(read_properties(p.stdout).get("versionCode", "1")) if p.returncode == 0 else 1
    return codes


def live_manifest(repo, hosts, keys, dry_run):
    """The currently published manifest (read only), signature-checked; None before the first release."""
    try:
        data, _ = fetch_public(manifest_url(repo), hosts, max_bytes=65536)
        if data is None:
            return None
        sig, _ = fetch_public(manifest_url(repo) + ".sig", hosts, max_bytes=128)
        if sig is None:
            raise ReleaseError("the live update.json has no update.json.sig")
        verify_signature(data, sig, keys)
        return json.loads(data)
    except (ReleaseError, OSError) as e:
        if dry_run:
            log(f"  warning (dry run): live manifest not checked: {e}")
            return None
        raise


def build_release(env, code, name):
    if os.path.isdir(APK_DIR):
        shutil.rmtree(APK_DIR)
    cmd = ["./gradlew", "--no-daemon", "--max-workers=2", f"-Pthebox.versionCode={code}", f"-Pthebox.versionName={name}", ":app:assembleRelease"]
    log("  " + " ".join(cmd))
    run(cmd, env=env, capture=False)


def collect_apks(env, code, name):
    """abi -> (path, facts). Every APK must be ours, this version, and signed by exactly the release certificate."""
    aapt2, apksigner = build_tool(env, "aapt2"), build_tool(env, "apksigner")
    found = {}
    for abi in ABIS:
        path = os.path.join(APK_DIR, f"app-{abi}-release.apk")
        if not os.path.isfile(path):
            raise ReleaseError(f"missing {os.path.relpath(path, REPO_ROOT)} (unsigned build? check keystore.properties)")
        facts = parse_badging(run([aapt2, "dump", "badging", path], env=env).stdout)
        if facts["package"] != PACKAGE_NAME or facts["versionCode"] != code or facts["versionName"] != name:
            raise ReleaseError(f"{abi}: built as {facts}, expected {PACKAGE_NAME} {code} {name}")
        signers = parse_signers(run([apksigner, "verify", "--print-certs", path], env=env).stdout)
        if signers != [RELEASE_CERT_SHA256]:
            raise ReleaseError(f"{abi}: signed by {signers}, expected the release certificate {RELEASE_CERT_SHA256}")
        found[abi] = (path, facts)
        log(f"  {abi:12} {os.path.getsize(path):>11,} bytes  minSdk {facts['minSdk']}  signer ok")
    min_sdks = {f["minSdk"] for _, f in found.values()}
    if len(min_sdks) != 1:
        raise ReleaseError(f"APKs disagree on minSdk: {min_sdks}")
    return found


def openssl_public_hex(pem):
    p = subprocess.run(["openssl", "pkey", "-in", pem, "-pubout", "-outform", "DER"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0 or len(p.stdout) != 44:
        raise ReleaseError(f"cannot read the Ed25519 key {pem}")
    return p.stdout[-32:].hex()


def openssl_sign(pem, data_path, sig_path):
    run(["openssl", "pkeyutl", "-sign", "-inkey", pem, "-rawin", "-in", data_path, "-out", sig_path])


def release_body(manifest, notes, tag, repo):
    lines = [notes.strip(), "", "### Install", "",
             "On the TV, install `TheBox-universal.apk` (or the ABI build for your device) with Downloader or `adb install`. "
             "Installed copies update themselves from Settings → About.", "",
             f"Permanent link to the newest build: `https://github.com/{repo}/releases/latest/download/TheBox-universal.apk`", "",
             "### Verify", "", f"Signing certificate SHA-256: `{manifest['signingCertSha256'][0]}`", "",
             "| APK | Size | SHA-256 |", "|---|---|---|"]
    for e in manifest["apks"]:
        lines.append(f"| `{e['url'].rsplit('/', 1)[-1]}` | {e['sizeBytes']:,} | `{e['sha256']}` |")
    lines += ["", "To go back to an older version: Settings → Backup and restore → back up, uninstall, install the older APK, restore.",
              "", f"Package `{manifest['packageName']}`, versionCode {manifest['versionCode']}, tag `{tag}`."]
    return "\n".join(lines)


def make_signed_manifest(args, apks, code, name, repo, tag, notes, withdrawn, keys):
    log("== manifest")
    if os.path.isdir(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR)
    entries = {}
    for abi, (path, _) in apks.items():
        dest = os.path.join(OUT_DIR, asset_name(abi))
        shutil.copyfile(path, dest)
        with open(dest, "rb") as fh:
            entries[abi] = (asset_name(abi), fh.read())
    min_sdk = next(iter(apks.values()))[1]["minSdk"]
    published_at = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")
    manifest = build_manifest(version_code=code, version_name=name, min_sdk=min_sdk, apks=entries, repo=repo, tag=tag,
                              notes=notes, published_at=published_at, withdrawn=withdrawn, rollout_percent=args.rollout)
    data = manifest_bytes(manifest)
    if len(data) > 65536:  # UpdateTimings.MANIFEST_MAX_BYTES: the app refuses a larger manifest before parsing
        raise ReleaseError(f"update.json is {len(data)} bytes; shorten the notes")
    mpath, spath = os.path.join(OUT_DIR, MANIFEST_NAME), os.path.join(OUT_DIR, SIGNATURE_NAME)
    with open(mpath, "wb") as fh:
        fh.write(data)
    openssl_sign(args.signing_key, mpath, spath)
    with open(spath, "rb") as fh:
        sig = fh.read()
    idx = verify_signature(data, sig, keys)
    for e in manifest["apks"]:
        if not e["url"].startswith(f"https://github.com/{repo}/releases/download/{tag}/"):
            raise ReleaseError(f"APK URL {e['url']} is not under releases/download/{tag}/")
    log(f"  {MANIFEST_NAME} {len(data)} bytes, signature verifies with app key #{idx + 1}")
    log(f"  manifest URL {manifest_url(repo)}")
    log(json.dumps({k: manifest[k] for k in ("sequence", "versionCode", "versionName", "minSdk", "signingCertSha256", "withdrawn")}))
    return manifest, data, sig, mpath, spath


def main(argv=None):
    ap = argparse.ArgumentParser(description="Build, sign and publish a release of The Box (see tools/release/README.md).")
    ap.add_argument("--dry-run", action="store_true", help="build, sign and verify into build/release/; no git or network writes")
    ap.add_argument("--notes", help="release notes; the first line is the update card's summary (required unless --dry-run)")
    ap.add_argument("--version-code", type=int, help="override the bumped versionCode")
    ap.add_argument("--version-name", help="override the bumped versionName (MAJOR.MINOR.PATCH)")
    ap.add_argument("--withdraw", type=int, action="append", default=[], help="versionCode to add to `withdrawn` (repeatable)")
    ap.add_argument("--rollout", type=int, default=100, help="rolloutPercent (default 100)")
    ap.add_argument("--signing-key", default=DEFAULT_SIGNING_KEY, help="Ed25519 PEM (default ~/.thebox-release/update-current.pem)")
    args = ap.parse_args(argv)
    dry = args.dry_run
    if not dry and not (args.notes and args.notes.strip()):
        ap.error("--notes is required for a release (its first line shows on the TV's update card)")
    notes = (args.notes or "Dry run build").strip()
    if not 0 <= args.rollout <= 100:
        ap.error("--rollout must be 0-100")

    log("== preflight")
    repo, keys = app_update_config()
    hosts = redirect_hosts()
    log(f"  repo {repo}; app trusts {len(keys)} manifest key(s); redirect allowlist {sorted(hosts)}")
    if not os.path.isfile(args.signing_key):
        raise ReleaseError(f"signing key not found: {args.signing_key}")
    signing_pub = openssl_public_hex(args.signing_key)
    if signing_pub not in keys:
        raise ReleaseError(f"{args.signing_key} is not one of the keys compiled into the app; installed apps would refuse it")
    log(f"  signing key {os.path.basename(args.signing_key)} = app key #{keys.index(signing_pub) + 1}")
    # Installed copies run the previous release, so the key must also be one *that* build trusts (key rotation, README).
    last_tag = latest_tag()
    trusted_live = list(keys)  # keys a currently published manifest may be signed with
    if last_tag:
        old = run(["git", "show", f"{last_tag}:{os.path.relpath(ENDPOINTS_KT, REPO_ROOT)}"], check=False)
        old_keys = app_update_config(src=old.stdout)[1] if old.returncode == 0 else []
        if signing_pub not in old_keys:
            raise ReleaseError(f"the signing key is not trusted by {last_tag}, which installed TVs run; sign with a key that build lists")
        log(f"  {last_tag} (installed TVs) trusts the signing key too")
        trusted_live += [k for k in old_keys if k not in trusted_live]
    if not shutil.which("openssl"):
        raise ReleaseError("openssl not found")
    preflight_git(dry)

    current = read_properties(read_text(VERSION_FILE))
    cur_code, cur_name = int(current["versionCode"]), current["versionName"]
    code = args.version_code if args.version_code is not None else cur_code + 1
    name = args.version_name if args.version_name is not None else bump_version_name(cur_name)
    tag = tag_for(name)
    if code < cur_code:
        raise ReleaseError(f"versionCode {code} is below version.properties' {cur_code}")
    tags = released_codes()
    if tag in tags:
        raise ReleaseError(f"tag {tag} already exists")
    live = live_manifest(repo, hosts, trusted_live, dry)
    check_newer(code, list(tags.values()) + ([live["versionCode"]] if live else []), live["sequence"] if live else None)
    if not dry:
        remote_tag = run(["git", "ls-remote", "--tags", "origin", f"refs/tags/{tag}"]).stdout.strip()
        if remote_tag:
            raise ReleaseError(f"tag {tag} already exists on origin")
        token = github_token()
    withdrawn = set(args.withdraw) | set(live.get("withdrawn", []) if live else [])
    log(f"  version {cur_code}/{cur_name} -> {code}/{name}, tag {tag}, sequence {sequence_for(code)}")

    log("== build")
    env = build_env()
    if not dry:
        write_version(VERSION_FILE, code, name)
    try:
        build_release(env, code, name)
        log("== verify APKs")
        apks = collect_apks(env, code, name)
        manifest, data, sig, mpath, spath = make_signed_manifest(args, apks, code, name, repo, tag, notes, withdrawn, keys)
    except BaseException:
        if not dry:
            git("checkout", "--", "version.properties")  # nothing was committed: leave the tree as it was
        raise

    if dry:
        log(f"== dry run complete: {os.path.relpath(OUT_DIR, REPO_ROOT)}/ (nothing committed, tagged, pushed or uploaded)")
        return 0
    log("== commit, tag, push")
    ident = ["-c", f"user.name={COMMIT_AUTHOR[0]}", "-c", f"user.email={COMMIT_AUTHOR[1]}"]
    if git("status", "--porcelain", "--", "version.properties"):
        git("add", "version.properties")
        git(*ident, "commit", "-m", f"Release {tag}",
            "-m", f"Bump versionCode to {code} and versionName to {name} for the {tag} sideload release.",
            "-m", COMMIT_TRAILER)
    git(*ident, "tag", "-a", tag, "-m", f"The Box {name} (versionCode {code})")
    run(["git", "push", "origin", "main"], capture=False)
    run(["git", "push", "origin", tag], capture=False)

    log("== GitHub release")
    gh = GitHub(repo, token)
    body = release_body(manifest, notes, tag, repo)
    rel = gh.create_draft(tag, f"The Box {name}", body)
    for abi in apks:
        gh.upload(rel, os.path.join(OUT_DIR, asset_name(abi)), asset_name(abi), "application/vnd.android.package-archive")
        log(f"  uploaded {asset_name(abi)}")
    gh.upload(rel, mpath, MANIFEST_NAME, "application/json")
    gh.upload(rel, spath, SIGNATURE_NAME, "application/octet-stream")
    log(f"  uploaded {MANIFEST_NAME}, {SIGNATURE_NAME}")
    rel = gh.publish(rel)
    log(f"  published {rel.get('html_url')}")

    log("== public check (as the app fetches it)")
    got, visited = fetch_public(manifest_url(repo), hosts)
    got_sig, _ = fetch_public(manifest_url(repo) + ".sig", hosts)
    if got != data or got_sig != sig:
        raise ReleaseError("latest/download does not serve the manifest just published yet (CDN lag?); re-check by hand")
    log(f"  manifest served via {' -> '.join(visited)}")
    apk_bytes, visited = fetch_public(manifest["apks"][-1]["url"], hosts)
    if apk_bytes is None or sha256_hex(apk_bytes) != manifest["apks"][-1]["sha256"]:
        raise ReleaseError("the universal APK download does not match the manifest")
    log(f"  universal APK served via {' -> '.join(visited)}, SHA-256 ok")
    log(f"== released {tag}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ReleaseError as e:
        print(f"release: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
