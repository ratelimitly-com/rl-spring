"""Compare unsigned POM/JAR bytes; signing time is intentionally not reproducible."""
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as first, zipfile.ZipFile(sys.argv[2]) as second:
    names = {n for n in first.namelist() if n.endswith((".jar", ".pom"))}
    if names != {n for n in second.namelist() if n.endswith((".jar", ".pom"))}:
        sys.exit("Unsigned artifact sets differ")
    for name in sorted(names):
        if first.read(name) != second.read(name):
            sys.exit("Non-reproducible artifact: " + name)
    print(f"Two clean builds agree byte-for-byte for all {len(names)} unsigned artifacts.")
