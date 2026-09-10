"""Validate the actual Central-plugin bundle before any future upload is allowed."""
import hashlib
import io
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ARTIFACTS = (
    "ratelimitly-spring-boot-parent",
    "ratelimitly-spring-boot-autoconfigure",
    "ratelimitly-spring-boot-starter",
)
HASHES = ("md5", "sha1", "sha256", "sha512")
LICENSE = (pathlib.Path(__file__).resolve().parent.parent / "LICENSE").read_bytes()


def verify_bundle(path, version, *, verify_signatures=True):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT|-dry-run)?", version):
        raise ValueError("invalid bundle version")
    unsigned = {}
    for artifact in ARTIFACTS:
        prefix = f"com/ratelimitly/{artifact}/{version}/{artifact}-{version}"
        suffixes = (".pom",) if artifact.endswith("-parent") else (".pom", ".jar", "-sources.jar", "-javadoc.jar")
        for suffix in suffixes:
            unsigned[prefix + suffix] = artifact
    expected = {name + suffix for name in unsigned for suffix in ("", ".asc", ".md5", ".sha1", ".sha256", ".sha512")}
    with zipfile.ZipFile(path) as archive:
        names = [i.filename for i in archive.infolist() if not i.is_dir()]
        if len(names) != len(set(names)) or set(names) != expected:
            raise ValueError("bundle must contain exactly the parent, autoconfigure and starter with signatures/checksums")
        for item in archive.infolist():
            if item.is_dir() and not any(name.startswith(item.filename) for name in expected):
                raise ValueError("unexpected bundle directory")
            if item.file_size > 50_000_000:
                raise ValueError("unexpectedly large bundle entry")
        for name, artifact in unsigned.items():
            data = archive.read(name)
            if not data or not archive.read(name + ".asc"):
                raise ValueError("empty artifact or signature")
            for algorithm in HASHES:
                expected_hash = hashlib.new(algorithm, data).hexdigest().encode()
                if archive.read(name + "." + algorithm).strip().lower() != expected_hash:
                    raise ValueError("checksum mismatch: " + name)
            if name.endswith(".pom"):
                root = ET.fromstring(data)
                for node in root.iter():
                    node.tag = node.tag.rsplit("}", 1)[-1]
                group = root.findtext("groupId") or root.findtext("parent/groupId")
                actual_version = root.findtext("version") or root.findtext("parent/version")
                if (group, root.findtext("artifactId"), actual_version) != ("com.ratelimitly", artifact, version):
                    raise ValueError("incorrect POM coordinates")
                if artifact.endswith("-parent"):
                    fields = ("name", "description", "url", "licenses/license/name", "licenses/license/url",
                              "developers/developer/name", "developers/developer/email", "scm/connection",
                              "scm/developerConnection", "scm/url")
                    if any(not (root.findtext(field) or "").strip() for field in fields):
                        raise ValueError("missing required parent POM metadata")
                elif tuple(root.findtext("parent/" + field) for field in ("groupId", "artifactId", "version")) != (
                        "com.ratelimitly", "ratelimitly-spring-boot-parent", version):
                    raise ValueError("child must reference the published parent in this bundle")
            else:
                with zipfile.ZipFile(io.BytesIO(data)) as jar:
                    licenses = [n for n in jar.namelist() if n == "LICENSE" or n.endswith("/LICENSE")]
                    # Javadoc may also contain the JDK's own legal/LICENSE.
                    # Require our complete MIT text without rejecting third-party notices.
                    if not any(jar.read(n) == LICENSE for n in licenses):
                        raise ValueError("missing MIT license in " + name)
                    if artifact.endswith("-starter") and name.endswith(("-sources.jar", "-javadoc.jar")):
                        if not jar.read("README.md"):
                            raise ValueError("dependency-only starter documentation is missing")
            if verify_signatures:
                with tempfile.TemporaryDirectory() as directory:
                    payload = pathlib.Path(directory) / "artifact"
                    signature = pathlib.Path(directory) / "artifact.asc"
                    payload.write_bytes(data)
                    signature.write_bytes(archive.read(name + ".asc"))
                    subprocess.run(["gpg", "--batch", "--verify", str(signature), str(payload)],
                                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return len(unsigned)


if __name__ == "__main__":
    try:
        count = verify_bundle(sys.argv[1], sys.argv[2])
        print(f"Verified exactly {len(ARTIFACTS)} coordinates / {count} signed artifacts; sample excluded.")
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit("Central bundle rejected: " + str(error))
