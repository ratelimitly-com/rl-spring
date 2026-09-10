"""Synthetic bundle tests: no credentials, Maven, or network required."""
import hashlib
import importlib.util
import io
import pathlib
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "verify_central_bundle", pathlib.Path(__file__).with_name("verify_central_bundle.py"))
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)
VERSION = "2.0.0-SNAPSHOT"
ROOT = pathlib.Path(__file__).resolve().parent.parent
METADATA = ('<name>Fixture</name><description>Fixture library</description>'
            '<url>https://github.com/ratelimitly-com/rl-spring</url>'
            '<licenses><license><name>MIT License</name><url>https://opensource.org/licenses/MIT</url>'
            '</license></licenses><developers><developer><name>Fixture</name>'
            '<email>ci@invalid</email></developer></developers><scm><connection>scm:git:fixture</connection>'
            '<developerConnection>scm:git:fixture</developerConnection><url>https://example.invalid/</url></scm>')


def jar():
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("META-INF/LICENSE", (ROOT / "LICENSE").read_bytes())
        archive.writestr("legal/LICENSE", "Additional Javadoc tool license fixture")
        archive.writestr("README.md", "Dependency-only starter fixture")
    return data.getvalue()


class CentralBundleTest(unittest.TestCase):
    def entries(self):
        result = {}
        for artifact in bundle.ARTIFACTS:
            prefix = "com/ratelimitly/" + artifact + "/" + VERSION + "/" + artifact + "-" + VERSION
            suffixes = (".pom",) if artifact.endswith("-parent") else (".pom", ".jar", "-sources.jar", "-javadoc.jar")
            for suffix in suffixes:
                metadata = METADATA if artifact.endswith("-parent") else (
                    '<parent><groupId>com.ratelimitly</groupId>'
                    '<artifactId>ratelimitly-spring-boot-parent</artifactId>'
                    f'<version>{VERSION}</version></parent>')
                data = (f'<project><modelVersion>4.0.0</modelVersion><groupId>com.ratelimitly</groupId>'
                        f'<artifactId>{artifact}</artifactId><version>{VERSION}</version>{metadata}</project>').encode() if suffix == ".pom" else jar()
                name = prefix + suffix
                result[name] = data
                result[name + ".asc"] = b"synthetic signature"
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    result[name + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()
        return result

    def verify(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "bundle.zip"
            with zipfile.ZipFile(path, "w") as archive:
                for name, value in entries.items():
                    archive.writestr(name, value)
            return bundle.verify_bundle(path, VERSION, verify_signatures=False)

    def test_exact_three_artifacts(self):
        self.assertEqual(self.verify(self.entries()), 9)

    def test_missing_parent_signature_or_hash_fails(self):
        for suffix in (".pom", ".asc", ".sha256"):
            entries = self.entries()
            entries.pop(next(name for name in entries if name.endswith(suffix)))
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                self.verify(entries)

    def test_sample_unexpected_file_and_traversal_fail(self):
        for name in ("com/ratelimitly/ratelimitly-spring-boot-sample-app/sample.pom", "extra.txt", "../outside"):
            entries = self.entries()
            entries[name] = b"unexpected"
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.verify(entries)

    def test_tampering_wrong_coordinates_and_empty_jars_fail(self):
        for suffix, value in ((".jar", b""), (".pom", b"<project/>"), (".sha1", b"0000")):
            entries = self.entries()
            entries[next(name for name in entries if name.endswith(suffix))] = value
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                self.verify(entries)

    def test_semantically_invalid_poms_with_valid_checksums_fail(self):
        cases = (("-parent", b"com.ratelimitly", b"wrong.group"),
                 ("-parent", b"<name>MIT License</name>", b"<name></name>"),
                 ("-starter", b"<artifactId>ratelimitly-spring-boot-parent</artifactId>",
                  b"<artifactId>unpublished-parent</artifactId>"))
        for artifact, old, new in cases:
            entries = self.entries()
            name = next(n for n in entries if artifact + "/" in n and n.endswith(".pom"))
            entries[name] = entries[name].replace(old, new)
            for algorithm in bundle.HASHES:
                entries[name + "." + algorithm] = hashlib.new(algorithm, entries[name]).hexdigest().encode()
            with self.subTest(artifact=artifact, field=old), self.assertRaises(ValueError):
                self.verify(entries)

    def test_metadata_and_deployment_exclusions_are_explicit(self):
        root = pathlib.Path(__file__).resolve().parent.parent
        pom = (root / "pom.xml").read_text()
        for value in ("<id>central-release</id>", "<developers>", "<scm ",
                      "<excludeArtifacts>", "<autoPublish>false</autoPublish>",
                      "<central.skipPublishing>true</central.skipPublishing>"):
            self.assertIn(value, pom)
        sample = (root / "ratelimitly-spring-boot-sample-app/pom.xml").read_text()
        self.assertIn("<maven.deploy.skip>true</maven.deploy.skip>", sample)
        self.assertFalse((root / ".github/workflows/release.yml").exists())
        workflow = (root / ".github/workflows/publish-mvn.yml").read_text()
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("contents: write", workflow)


if __name__ == "__main__":
    unittest.main()
