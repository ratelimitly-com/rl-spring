"""Pre-upload artifact identity checks, without invoking Maven or a registry."""
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('check-reviewed-artifacts.py')


class ReviewedArtifactsTest(unittest.TestCase):
    def test_matching_artifacts_pass_and_changed_or_missing_artifacts_fail(self):
        for artifact in ('ratelimitly-spring-boot-parent', 'ratelimitly-spring-boot-autoconfigure',
                         'ratelimitly-spring-boot-starter'):
            with tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                module, expected = root / 'module', root / 'expected'
                (module / 'target').mkdir(parents=True)
                expected.mkdir()
                (module / 'pom.xml').write_bytes(b'pom')
                (expected / (artifact + '-2.0.0.pom')).write_bytes(b'pom')
                if not artifact.endswith('-parent'):
                    for suffix in ('.jar', '-sources.jar', '-javadoc.jar'):
                        name = artifact + '-2.0.0' + suffix
                        (module / 'target' / name).write_bytes(b'jar')
                        (expected / name).write_bytes(b'jar')
                command = ['python3', str(SCRIPT), str(expected), str(module), artifact, '2.0.0']
                self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
                (module / 'pom.xml').write_bytes(b'tampered')
                self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
                (module / 'pom.xml').unlink()
                self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)


if __name__ == '__main__':
    unittest.main()
