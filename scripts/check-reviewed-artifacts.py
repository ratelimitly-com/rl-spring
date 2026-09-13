"""Per-module guard, executed by Maven before deploy; not active in normal builds."""
import pathlib
import sys

expected, module, artifact, version = sys.argv[1:]
if expected == '${release.expectedArtifacts}':
    sys.exit(0)
if artifact == 'ratelimitly-spring-boot-sample-app':
    sys.exit(0)
allowed = {'ratelimitly-spring-boot-parent', 'ratelimitly-spring-boot-autoconfigure',
           'ratelimitly-spring-boot-starter'}
if artifact not in allowed:
    sys.exit('Unexpected release module')
base = pathlib.Path(module)
files = {artifact + '-' + version + '.pom': base / 'pom.xml'}
if artifact != 'ratelimitly-spring-boot-parent':
    for suffix in ('.jar', '-sources.jar', '-javadoc.jar'):
        name = artifact + '-' + version + suffix
        files[name] = base / 'target' / name
for name, source in files.items():
    if source.read_bytes() != (pathlib.Path(expected) / name).read_bytes():
        sys.exit('Reviewed release artifact mismatch: ' + name)
print('Reviewed release artifacts match: ' + artifact)
