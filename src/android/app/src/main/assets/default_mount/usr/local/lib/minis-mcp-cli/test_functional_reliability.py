"""Offline regressions for lost tool replies and SSE response correlation."""
import json
import pathlib
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import daemon
import main as cli
from transport import http, stdio


class ToolRetryTests(unittest.TestCase):
    def test_cli_reply_loss_is_not_reported_as_safe_to_resend(self):
        sock = types.SimpleNamespace(settimeout=lambda value: None, connect=lambda target: None,
                                     sendall=lambda value: None, recv=lambda n: b"", close=lambda: None)
        with patch.object(cli, "_read_daemon_port", return_value=12345), \
                patch.object(cli.socket, "socket", return_value=sock):
            self.assertEqual("OUTCOME_UNKNOWN", cli.send_to_daemon({"cmd": "call"})["code"])
            self.assertEqual("NO_DAEMON", cli.send_to_daemon({"cmd": "tools"})["code"])

    def test_daemon_lost_reply_does_not_repeat_an_executed_tool(self):
        child = """
import json, pathlib, sys
counter = pathlib.Path(sys.argv[1])
for line in sys.stdin:
    body = json.loads(line)
    if body['method'] == 'initialize':
        print(json.dumps({'jsonrpc':'2.0','id':body['id'],'result':{}}), flush=True)
    elif body['method'] == 'tools/call':
        counter.write_text(str(int(counter.read_text()) + 1) if counter.exists() else '1')
        sys.exit(0)
"""
        with tempfile.TemporaryDirectory() as directory:
            counter = pathlib.Path(directory) / "side-effects"
            cfg = {"command": sys.executable, "args": ["-B", "-c", child, str(counter)]}
            server = daemon.DaemonServer("unused", "unused")
            try:
                with patch.object(daemon.config, "get_server", return_value=cfg), \
                        patch.object(daemon.deps, "ensure_command"):
                    result = server.handle_request({"cmd": "call", "server": "fixture", "tool": "write"})
                self.assertFalse(result["ok"])
                self.assertEqual("OUTCOME_UNKNOWN", result["code"])
                self.assertEqual("1", counter.read_text())
                self.assertNotIn("fixture", server.pool._pool)
            finally:
                server.pool.shutdown()

    def test_daemon_timeout_does_not_retry_but_read_only_query_can_retry(self):
        pool = daemon.MCPPool(on_empty=lambda: None)
        attempts = []
        def invoke(session):
            attempts.append(session)
            if len(attempts) == 1:
                raise http.MCPError("TIMEOUT", "lost reply")
            return "ok"
        with patch.object(pool, "get", return_value=object()), patch.object(pool, "evict") as evict:
            with self.assertRaises(http.MCPError) as failure:
                pool.call_with_retry("fixture", invoke, retry_safe=False)
            self.assertEqual("OUTCOME_UNKNOWN", failure.exception.code)
            self.assertEqual(1, len(attempts))
            evict.assert_called_once_with("fixture")
            attempts.clear()
            self.assertEqual("ok", pool.call_with_retry("fixture", invoke))
            self.assertEqual(2, len(attempts))

    def test_legacy_stdio_retries_handshake_only_not_lost_tool_reply(self):
        transport = stdio.STDIOTransport({"command": "fixture"}, "fixture")
        process = types.SimpleNamespace(terminate=lambda: None, wait=lambda **kw: None, stdin=None, stdout=None)
        with patch.object(transport, "_spawn", return_value=process) as spawn, \
                patch.object(transport, "_handshake") as handshake, \
                patch.object(transport, "_rpc", side_effect=http.MCPError("STDIO_CRASH", "lost reply")):
            with self.assertRaises(http.MCPError) as failure:
                transport.call_tool("write", {})
            self.assertEqual("OUTCOME_UNKNOWN", failure.exception.code)
            self.assertEqual(1, spawn.call_count)
            self.assertEqual(1, handshake.call_count)
        with patch.object(transport, "_spawn", return_value=process) as spawn, \
                patch.object(transport, "_handshake", side_effect=[http.MCPError("STDIO_CRASH", "init"), None]), \
                patch.object(transport, "_rpc", return_value={"ok": True}) as rpc:
            self.assertEqual({"ok": True}, transport.call_tool("write", {}))
            self.assertEqual(2, spawn.call_count)
            self.assertEqual(1, rpc.call_count)

    def test_tool_error_mentioning_session_does_not_reexecute_http_tool(self):
        client = http.HTTPTransport({"url": "http://fixture.invalid"}, "fixture")
        client._initialized = True
        with patch.object(client, "_post", side_effect=http.MCPError("MCP_ERROR", "session changed after write")) as post:
            with self.assertRaises(http.MCPError) as failure:
                client.call_tool("write", {})
            self.assertEqual("MCP_ERROR", failure.exception.code)
            self.assertEqual(1, post.call_count)

    def test_http_timeout_returns_unknown_without_resending(self):
        client = http.HTTPTransport({"url": "http://fixture.invalid"}, "fixture")
        client._initialized = True
        with patch.object(client, "_post", side_effect=http.MCPError("TIMEOUT", "lost reply")) as post:
            with self.assertRaises(http.MCPError) as failure:
                client.call_tool("write", {})
            self.assertEqual("OUTCOME_UNKNOWN", failure.exception.code)
            self.assertEqual(1, post.call_count)
            self.assertFalse(client._initialized)


class SseResponseTests(unittest.TestCase):
    @staticmethod
    def response(text, content_type="text/event-stream"):
        return types.SimpleNamespace(text=text, headers={"content-type": content_type})

    def test_multiline_event_comments_crlf_and_eof(self):
        body = ': heartbeat\r\nevent: message\r\nid: event-1\r\ndata: {"jsonrpc":"2.0","id":7,\r\n'
        body += 'data: "result":{"content":[{"type":"text","text":"ok"}]}}'
        self.assertEqual("ok", http._parse_response(self.response(body), 7)["result"]["content"][0]["text"])

    def test_notification_and_other_response_cannot_replace_requested_reply(self):
        events = [{"jsonrpc": "2.0", "method": "notifications/tools/list_changed"},
                  {"jsonrpc": "2.0", "id": 99, "result": "other"},
                  {"jsonrpc": "2.0", "id": 7, "result": "expected"},
                  {"jsonrpc": "2.0", "method": "notifications/tools/list_changed"}]
        body = "".join("data: " + json.dumps(event) + "\n\n" for event in events)
        self.assertEqual("expected", http._parse_response(self.response(body), 7)["result"])

    def test_utf8_bom_and_empty_keepalive_event_do_not_hide_the_reply(self):
        body = '\ufeffdata:\n\n: heartbeat\n\ndata: {"jsonrpc":"2.0","id":7,"result":"ok"}\n\n'
        self.assertEqual("ok", http._parse_response(self.response(body), 7)["result"])

    def test_matching_error_is_not_hidden_by_a_later_result(self):
        body = 'data: {"jsonrpc":"2.0","id":7,"error":{"message":"tool failed"}}\n\n'
        body += 'data: {"jsonrpc":"2.0","id":99,"result":"other"}\n\n'
        self.assertEqual("tool failed", http._parse_response(self.response(body), 7)["error"]["message"])

    def test_missing_id_and_malformed_payload_are_rejected(self):
        for body in ['data: {"jsonrpc":"2.0","id":99,"result":{}}\n\n',
                     'data: {"jsonrpc":"2.0","method":"notification"}\n\n',
                     'data: {broken\n\n', ': comment\n\n']:
            with self.assertRaises(http.MCPError) as failure:
                http._parse_response(self.response(body), 7)
            self.assertEqual("PARSE_ERROR", failure.exception.code)

    def test_json_body_must_also_match_id(self):
        with self.assertRaises(http.MCPError):
            http._parse_response(self.response('{"jsonrpc":"2.0","id":8,"result":{}}', "application/json"), 7)

    def test_call_tool_passes_its_request_id_to_sse_parser(self):
        client = http.HTTPTransport({"url": "http://fixture.invalid"}, "fixture")
        client._initialized = True
        response = self.response('data: {"jsonrpc":"2.0","id":1,"result":{"ok":true}}\n\n'
                                 'data: {"jsonrpc":"2.0","method":"notification"}\n\n')
        response.status_code = 200
        fake_httpx = types.SimpleNamespace(post=lambda *a, **kw: response,
                                          TimeoutException=TimeoutError, HTTPError=OSError)
        with patch.object(http, "httpx", fake_httpx):
            self.assertEqual({"ok": True}, client.call_tool("read", {}))


if __name__ == "__main__":
    unittest.main(verbosity=2)
