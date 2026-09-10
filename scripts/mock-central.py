"""Loopback-only Central upload/validation fixture. Never publishes a component."""
import http.server
import json
import pathlib
import sys
import urllib.parse

DEPLOYMENT = "00000000-0000-0000-0000-000000000001"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Do not log authentication headers, even for synthetic credentials.

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 <= length <= 50_000_000:
            self.send_error(413)
            return
        self.rfile.read(length)
        if url.path == "/api/v1/publisher/upload" and query.get("publishingType") == ["USER_MANAGED"]:
            self.reply(201, DEPLOYMENT.encode(), "text/plain")
        elif url.path == "/api/v1/publisher/status" and query.get("id") == [DEPLOYMENT]:
            self.reply(200, json.dumps({"deploymentId": DEPLOYMENT,
                                       "deploymentState": "VALIDATED"}).encode(), "application/json")
        else:
            # In particular, automatic publication and deployment publish/delete
            # endpoints are not supported by this fixture.
            self.send_error(400, "Unexpected publication request")

    def reply(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    pathlib.Path(sys.argv[1]).write_text(str(server.server_port), encoding="ascii")
    server.serve_forever()
