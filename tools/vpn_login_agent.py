# -*- coding: utf-8 -*-
"""Login to new-api gateway with account/password, then create an API token."""
import http.client
import json

HOST = "172.20.10.3"
PORT = 3000
USER = "chenkai"
PASS = "kai@1234"


def req(method, path, body=None, token=None):
    c = http.client.HTTPConnection(HOST, PORT, timeout=5)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body) if body is not None else None
    c.request(method, path, body=payload, headers=headers)
    r = c.getresponse()
    data = r.read().decode("utf-8", "replace")
    c.close()
    return r.status, data


# 1) login
print("[1] POST /api/user/login", flush=True)
st, body = req("POST", "/api/user/login", {"username": USER, "password": PASS})
print("  status=%d body=%s" % (st, body[:500]), flush=True)

try:
    j = json.loads(body)
except Exception:
    print("LOGIN-FAILED (non-json)")
    raise SystemExit(1)

if not j.get("success"):
    print("LOGIN-FAILED: %s" % j.get("message"))
    raise SystemExit(1)

data = j.get("data", {})
access_token = data.get("access_token") or data.get("token") or ""
print("  login OK, role=%s" % data.get("role"))
print("  access_token=%s..." % access_token[:20])

if not access_token:
    print("NO-ACCESS-TOKEN")
    raise SystemExit(1)

# 2) try using access_token directly as OpenAI key on /v1/models
print("[2] GET /v1/models with access_token", flush=True)
st, body = req("GET", "/v1/models", token=access_token)
print("  status=%d body=%s" % (st, body[:600]), flush=True)

# 3) list existing tokens via /api/token/
print("[3] GET /api/token/", flush=True)
st, body = req("GET", "/api/token/", token=access_token)
print("  status=%d body=%s" % (st, body[:800]), flush=True)

print("SCRIPT-DONE")
