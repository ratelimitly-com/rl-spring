"""Collect exactly the publishable reactor artifacts, excluding all sample files."""
import hashlib
import pathlib
import sys
import zipfile
from verify_maven_bundle import ARTIFACTS, HASHES, verify_bundle

repository, version, destination = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3])
expected = set()
with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
    for artifact in ARTIFACTS:
        prefix = f'com/ratelimitly/{artifact}/{version}/{artifact}-{version}'
        suffixes = ('.pom',) if artifact.endswith('-parent') else ('.pom', '.jar', '-sources.jar', '-javadoc.jar')
        for suffix in suffixes:
            name = prefix + suffix
            expected.add(name)
            payload = (repository / name).read_bytes()
            bundle.writestr(name, payload)
            bundle.writestr(name + '.asc', (repository / (name + '.asc')).read_bytes())
            for algorithm in HASHES:
                bundle.writestr(name + '.' + algorithm, hashlib.new(algorithm, payload).hexdigest())
    actual = {str(p.relative_to(repository)) for p in repository.rglob('*') if p.suffix in ('.jar', '.pom')}
    if actual != expected:
        raise ValueError('unexpected deployed artifact set (including sample)')
verify_bundle(destination, version)
