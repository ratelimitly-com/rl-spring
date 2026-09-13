"""Exercise the loopback deploy fixture, including filesystem escape attempts."""
import http.client
import pathlib
import subprocess
import sys
import tempfile
import time
import unittest


class MavenFixtureTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = pathlib.Path(temporary.name)
        self.root = self.base / 'repository'
        self.root.mkdir()
        port_file = self.base / 'port'
        self.process = subprocess.Popen(
            [sys.executable, str(pathlib.Path(__file__).with_name('mock-maven.py')),
             str(self.root), str(port_file)], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL)
        self.addCleanup(self.stop_server)
        deadline = time.monotonic() + 5
        while not port_file.exists() or not port_file.read_text():
            if self.process.poll() is not None or time.monotonic() >= deadline:
                self.fail('fixture failed to start')
            time.sleep(0.01)
        self.port = int(port_file.read_text())

    def stop_server(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)

    def request(self, method, path, data=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        try:
            connection.request(method, path, body=data)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def test_artifact_round_trip_and_duplicate_rejection(self):
        for prefix in ('first', 'second', 'rejected'):
            path = '/' + prefix + '/com/ratelimitly/example/1.0.0/example.jar'
            self.assertEqual(self.request('PUT', path, b'jar')[0], 201)
            self.assertEqual(self.request('GET', path), (200, b'jar'))
            self.assertEqual(self.request('PUT', path, b'changed')[0], 409)
            self.assertEqual(self.request('GET', path), (200, b'jar'))

    def test_metadata_can_be_updated(self):
        path = '/first/com/ratelimitly/example/maven-metadata.xml'
        for data in (b'first', b'second'):
            self.assertEqual(self.request('PUT', path, data)[0], 201)
            self.assertEqual(self.request('GET', path), (200, data))

    def test_traversal_and_unknown_prefix_are_rejected(self):
        for path in ('/first/../../outside', '/first/%2e%2e/%2e%2e/outside',
                     '/first/..%2f..%2foutside', '/other/file',
                     '/first/..%5c..%5coutside', '/first/%00file'):
            with self.subTest(path=path):
                self.assertEqual(self.request('GET', path)[0], 404)
                self.assertEqual(self.request('PUT', path, b'escape')[0], 400)
        self.assertFalse((self.base / 'outside').exists())

    def test_symlink_cannot_read_or_write_outside_root(self):
        # A sibling sharing the root's name also tests the separator boundary.
        outside = self.base / 'repository-other'
        outside.mkdir()
        (outside / 'existing').write_bytes(b'outside')
        (self.root / 'first').mkdir()
        (self.root / 'first' / 'link').symlink_to(outside, target_is_directory=True)
        self.assertEqual(self.request('GET', '/first/link/existing')[0], 404)
        self.assertEqual(self.request('PUT', '/first/link/new', b'escape')[0], 400)
        self.assertEqual((outside / 'existing').read_bytes(), b'outside')
        self.assertFalse((outside / 'new').exists())


if __name__ == '__main__':
    unittest.main()
