"""Loopback Maven deploy fixture: duplicate artifact PUTs fail, never proxy traffic."""
import http.server
import os
import pathlib
import sys
import urllib.parse

root = os.path.realpath(sys.argv[1])


class Handler(http.server.BaseHTTPRequestHandler):
    def file(self):
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        if (not path.startswith(('/first/', '/second/', '/rejected/'))
                or '..' in pathlib.PurePosixPath(path).parts or '\\' in path or '\x00' in path):
            raise ValueError('invalid fixture path')
        # Resolve before checking containment: lexical validation alone cannot
        # prevent a symlink from pointing outside the private scratch directory.
        candidate = os.path.realpath(os.path.join(root, path.lstrip('/')))
        if not candidate.startswith(root + os.sep):
            raise ValueError('fixture path escapes root')
        return pathlib.Path(candidate)

    def do_PUT(self):
        try:
            path = self.file()
            length = int(self.headers.get('Content-Length', '0'))
            if length < 1 or length > 50_000_000:
                raise ValueError('invalid size')
            if path.exists() and not path.name.startswith('maven-metadata.xml'):
                self.send_error(409, 'duplicate artifact')
                return
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.rfile.read(length))
            self.send_response(201)
            self.end_headers()
        except (ValueError, OSError):
            self.send_error(400)

    def do_GET(self):
        try:
            data = self.file().read_bytes()
        except (ValueError, OSError):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
pathlib.Path(sys.argv[2]).write_text(str(server.server_port))
server.serve_forever()
