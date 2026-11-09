"""Unit tests for the release manifest builder (tools/release/release.py). Stdlib only:

    python3 -I tools/release/test_release.py            # run
    python3 -I tools/release/test_release.py --regen    # rewrite the Kotlin cross-check fixture after a schema change

The fixture (data/src/test/resources/release/) is a manifest made by `release.build_manifest` and signed with
[FIXTURE_SEED], a throwaway test key that nothing trusts outside the tests. `ReleaseManifestFixtureTest` in `:data`
feeds it to the app's real `ManifestVerifier` with Tink's `Ed25519Verify`; the test below checks the checked-in bytes
are exactly what the builder produces today, so the two cannot drift apart.
"""
import hashlib
import importlib.util
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


release = _load("thebox_release", os.path.join(HERE, "release.py"))
ed25519 = release.load_ed25519()

# Test key only: derived from a public string, so it can sign nothing an installed app accepts.
FIXTURE_SEED = hashlib.sha256(b"thebox release fixture test key (not a real key)").digest()
FIXTURE_DIR = os.path.join(REPO_ROOT, "data", "src", "test", "resources", "release")
FIXTURE_APKS = {  # abi -> fake APK bytes (only their size and SHA-256 enter the manifest)
    "armeabi-v7a": b"fake armeabi-v7a apk",
    "arm64-v8a": b"fake arm64-v8a apk!",
    "universal": b"fake universal apk with both ABIs",
}


def fixture_manifest():
    apks = {abi: (release.asset_name(abi), data) for abi, data in FIXTURE_APKS.items()}
    return release.build_manifest(
        version_code=7, version_name="0.1.6", min_sdk=25, apks=apks, repo="shane9b3/the_box", tag="v0.1.6",
        notes="Fixes catch-up on Flussonic servers\n- Faster zapping", published_at="2026-11-08T12:00:00Z", withdrawn=[5])


def fixture_bytes():
    data = release.manifest_bytes(fixture_manifest())
    return data, ed25519.sign(FIXTURE_SEED, data)


class ManifestBuilderTest(unittest.TestCase):
    def test_field_names_match_the_kotlin_contract(self):
        m = fixture_manifest()
        # UpdateManifest (core/.../update/UpdateContracts.kt), in declaration order.
        self.assertEqual(list(m), ["schema", "sequence", "packageName", "channel", "versionCode", "versionName", "minSdk",
                                   "publishedAt", "notes", "apks", "signingCertSha256", "withdrawn", "rolloutPercent"])
        self.assertEqual([list(e) for e in m["apks"]], [["abi", "url", "sizeBytes", "sha256"]] * 3)
        self.assertEqual(m["schema"], 1)
        self.assertEqual(m["packageName"], "app.thebox")
        self.assertEqual(m["rolloutPercent"], 100)
        self.assertEqual(m["withdrawn"], [5])

    def test_contract_source_still_has_these_fields(self):
        src = release.read_text(os.path.join(REPO_ROOT, "core", "src", "main", "kotlin", "app", "thebox", "core", "update", "UpdateContracts.kt"))
        for field in fixture_manifest():
            self.assertRegex(src, rf"val {field}:", field)
        for field in ("abi", "url", "sizeBytes", "sha256"):
            self.assertIn(f"val {field}:", src)

    def test_sha256_and_size_per_apk(self):
        m = fixture_manifest()
        for e in m["apks"]:
            raw = FIXTURE_APKS[e["abi"]]
            self.assertEqual(e["sizeBytes"], len(raw))
            self.assertEqual(e["sha256"], hashlib.sha256(raw).hexdigest().upper())
            self.assertRegex(e["sha256"], r"^[0-9A-F]{64}$")

    def test_urls_are_under_the_tag_and_the_manifest_under_latest(self):
        m = fixture_manifest()
        self.assertEqual([e["url"] for e in m["apks"]], [
            "https://github.com/shane9b3/the_box/releases/download/v0.1.6/TheBox-armeabi-v7a.apk",
            "https://github.com/shane9b3/the_box/releases/download/v0.1.6/TheBox-arm64-v8a.apk",
            "https://github.com/shane9b3/the_box/releases/download/v0.1.6/TheBox-universal.apk",
        ])
        self.assertEqual(release.manifest_url("shane9b3/the_box"),
                         "https://github.com/shane9b3/the_box/releases/latest/download/update.json")

    def test_app_config_is_read_from_the_kotlin_source(self):
        repo, keys = release.app_update_config()
        self.assertEqual(repo, "shane9b3/the_box")
        self.assertEqual(keys[0], "dc0243f367bda3024ea97a4b82777d41abd9b0ea4a92fbc607be1009af5ad7ba")
        self.assertEqual(len(keys), 2)
        self.assertTrue({"github.com", "release-assets.githubusercontent.com"} <= release.redirect_hosts())

    def test_cert_digest_is_upper_hex_without_colons(self):
        m = release.build_manifest(version_code=2, version_name="0.0.2", min_sdk=25,
                                   apks={"universal": ("TheBox-universal.apk", b"x")}, repo="o/r", tag="v0.0.2", notes="n",
                                   published_at="t", cert_sha256="22:40:93:19:e0:1c:49:12:6c:e4:c4:62:07:f1:eb:50:5e:2d:2b:fc:46:9e:01:a7:c0:ea:04:17:e3:3c:46:34")
        self.assertEqual(m["signingCertSha256"], [release.RELEASE_CERT_SHA256])

    def test_universal_apk_is_required(self):
        with self.assertRaises(release.ReleaseError):
            release.build_manifest(version_code=2, version_name="0.0.2", min_sdk=25, apks={"arm64-v8a": ("a.apk", b"x")},
                                   repo="o/r", tag="v0.0.2", notes="n", published_at="t")

    def test_sequence_is_the_version_code_and_strictly_increases(self):
        self.assertEqual(release.sequence_for(7), 7)
        seqs = [release.sequence_for(c) for c in range(1, 50)]
        self.assertEqual(seqs, sorted(set(seqs)))
        release.check_newer(8, [1, 5, 7], previous_sequence=7)
        with self.assertRaises(release.ReleaseError):
            release.check_newer(7, [1, 5, 7])
        with self.assertRaises(release.ReleaseError):
            release.check_newer(8, [1], previous_sequence=8)
        with self.assertRaises(release.ReleaseError):
            release.sequence_for(0)

    def test_version_bump_and_tag(self):
        self.assertEqual(release.bump_version_name("0.0.1"), "0.0.2")
        self.assertEqual(release.bump_version_name("1.9.9"), "1.9.10")
        self.assertEqual(release.tag_for("1.2.3"), "v1.2.3")
        with self.assertRaises(release.ReleaseError):
            release.bump_version_name("0.0.1-m0")

    def test_signature_round_trip_and_tamper(self):
        data, sig = fixture_bytes()
        pub = ed25519.public_key(FIXTURE_SEED).hex()
        self.assertEqual(len(sig), 64)
        self.assertEqual(release.verify_signature(data, sig, ["00" * 32, pub]), 1)
        with self.assertRaises(release.ReleaseError):
            release.verify_signature(data.replace(b'"versionCode": 7', b'"versionCode": 8'), sig, [pub])
        with self.assertRaises(release.ReleaseError):
            release.verify_signature(data, sig, release.app_update_config()[1])  # the release keys never trust the test key

    def test_openssl_signature_verifies_with_the_pure_python_verifier(self):
        """The release path signs with OpenSSL and verifies in Python; prove both agree, with a throwaway key."""
        import shutil, subprocess, tempfile
        if not shutil.which("openssl"):
            self.skipTest("no openssl")
        with tempfile.TemporaryDirectory() as d:
            pem, msg, sig = (os.path.join(d, n) for n in ("k.pem", "m.json", "m.sig"))
            if subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519", "-out", pem], capture_output=True).returncode:
                self.skipTest("openssl without Ed25519")
            data, _ = fixture_bytes()
            with open(msg, "wb") as fh:
                fh.write(data)
            release.openssl_sign(pem, msg, sig)
            pub = release.openssl_public_hex(pem)
            with open(sig, "rb") as fh:
                self.assertEqual(release.verify_signature(data, fh.read(), [pub]), 0)

    def test_parsers(self):
        b = release.parse_badging("package: name='app.thebox' versionCode='7' versionName='0.1.6' platformBuildVersionName='15'\n"
                                  "minSdkVersion:'25'\ntargetSdkVersion:'35'\n")
        self.assertEqual(b, {"package": "app.thebox", "versionCode": 7, "versionName": "0.1.6", "minSdk": 25})
        s = release.parse_signers("Signer #1 certificate DN: CN=The Box\n"
                                  "Signer #1 certificate SHA-256 digest: 22409319e01c49126ce4c46207f1eb505e2d2bfc469e01a7c0ea0417e33c4634\n"
                                  "Signer #1 certificate SHA-1 digest: 00\n")
        self.assertEqual(s, [release.RELEASE_CERT_SHA256])
        self.assertEqual(release.read_properties("# c\nversionCode=3\nversionName = 1.0.0\n"), {"versionCode": "3", "versionName": "1.0.0"})

    def test_checked_in_fixture_is_what_the_builder_makes(self):
        data, sig = fixture_bytes()
        with open(os.path.join(FIXTURE_DIR, "update.json"), "rb") as fh:
            self.assertEqual(fh.read(), data, "builder output changed: run test_release.py --regen and rerun the :data test")
        with open(os.path.join(FIXTURE_DIR, "update.json.sig"), "rb") as fh:
            self.assertEqual(fh.read(), sig)
        with open(os.path.join(FIXTURE_DIR, "test_public_key.hex")) as fh:
            self.assertEqual(fh.read().strip(), ed25519.public_key(FIXTURE_SEED).hex())


def regen():
    os.makedirs(FIXTURE_DIR, exist_ok=True)
    data, sig = fixture_bytes()
    with open(os.path.join(FIXTURE_DIR, "update.json"), "wb") as fh:
        fh.write(data)
    with open(os.path.join(FIXTURE_DIR, "update.json.sig"), "wb") as fh:
        fh.write(sig)
    with open(os.path.join(FIXTURE_DIR, "test_public_key.hex"), "w") as fh:
        fh.write(ed25519.public_key(FIXTURE_SEED).hex() + "\n")
    print(f"wrote {os.path.relpath(FIXTURE_DIR, REPO_ROOT)}/")


if __name__ == "__main__":
    if "--regen" in sys.argv:
        regen()
    else:
        unittest.main()
