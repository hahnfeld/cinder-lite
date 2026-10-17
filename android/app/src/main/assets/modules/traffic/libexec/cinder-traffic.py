"""mitmproxy add-on for Cinder Lite's "Watch network traffic" (TrafficVpn starts mitmdump with it).

- HTTPS is decrypted only once the user has trusted Cinder Lite's certificate in Settings (the app
  then leaves /tmp/cinder-traffic/decrypt). Until then every connection passes through untouched
  and only its hostname is noted.
- Most apps don't accept a user-installed certificate. When one rejects it, that host passes
  through untouched from then on, so the app keeps working; its entries say so.
- DNS over TLS (port 853) always passes through: the phone's own resolver would refuse it.
- Each request is summarised in index.jsonl, for the `traffic` helper; the whole flows are in flows,
  written here rather than by save_stream_file, which buffers them out of the helper's sight.
"""
import json
import os
import time

from mitmproxy import io

DIR = "/tmp/cinder-traffic"
refused = set()   # hosts whose apps rejected the certificate


def note(entry):
    with open(f"{DIR}/index.jsonl", "a") as f:
        f.write(json.dumps(entry) + "\n")


def tls_clienthello(data):
    host, port = data.context.server.address
    host = data.client_hello.sni or host
    if port == 853:
        why = "DNS over TLS, passed through"
    elif host in refused:
        why = "encrypted: the app doesn't accept Cinder Lite's certificate"
    elif not os.path.exists(f"{DIR}/decrypt"):
        why = "encrypted: Cinder Lite's certificate isn't trusted"
    else:
        return
    data.ignore_connection = True
    note({"t": time.time(), "host": host, "port": port, "note": why})


def tls_failed_client(data):
    host = data.conn.sni or data.context.server.address[0]
    if host and host not in refused:
        refused.add(host)
        note({"t": time.time(), "host": host, "port": data.context.server.address[1],
              "note": "the app rejected Cinder Lite's certificate: passing this host through from now on"})


def response(flow):
    record(flow)


def error(flow):
    record(flow)


def record(flow):
    with open(f"{DIR}/flows", "ab") as f:
        io.FlowWriter(f).add(flow)
    req, res = flow.request, flow.response
    note({
        "t": req.timestamp_start, "id": flow.id, "method": req.method, "url": req.pretty_url,
        "status": res.status_code if res else None,
        "type": (res.headers.get("content-type", "") if res else "").split(";")[0],
        "size": len(res.raw_content or b"") if res else 0,
        "error": flow.error.msg if flow.error else None,
    })
