#!/usr/bin/env python3
"""
test_connections.py — ACME CSE protocol connection tests

Simulates the Android app's OneM2M session to validate all 4 protocol
bindings from inside the Docker container.

Run inside the acme-cse container:
    docker exec acme-cse python3 /test_connections.py

Tests:
  1. HTTP  — full OneM2M flow: AE register, CNT, SUB, CIN, notification
  2. WebSocket — same flow via persistent WS connection + verify nu=originator fix
  3. MQTT  — basic connectivity (pub/sub on broker)
  4. CoAP  — GET CSE-Base resource

Exit code: 0 = all passed, 1 = at least one failed
"""

import asyncio
import json
import sys
import time
import threading

import requests
import paho.mqtt.client as mqtt

try:
    import websockets
    HAS_WS = True
except ImportError:
    HAS_WS = False

# ── Configuration ──────────────────────────────────────────────────────────
CSE_HOST        = "localhost"
HTTP_PORT       = 8080
WS_PORT         = 8180
MQTT_HOST       = "mosquitto"   # Docker service name
MQTT_PORT       = 1883
COAP_PORT       = 5683

CSE_BASE        = "/id-in"
AE_ORIGINATOR   = "Ctest123"       # must start with C
AE_NAME         = "test-ae"
HTTP_HEADERS    = {
    "X-M2M-RI":     "req-{n}",
    "X-M2M-Origin": AE_ORIGINATOR,
    "X-M2M-RVI":    "3",
    "Content-Type": "application/json;ty={ty}",
    "Accept":       "application/json",
}

PASS = "\033[92m✓\033[0m"
FAIL = "\033[91m✗\033[0m"
INFO = "\033[94m→\033[0m"

results = {}

# ── Helpers ────────────────────────────────────────────────────────────────

_rqi = 0
def next_rqi():
    global _rqi
    _rqi += 1
    return f"rqi-{_rqi}"

def h(ty=None, origin=None):
    """Build OneM2M HTTP headers."""
    hdrs = {
        "X-M2M-RI":     next_rqi(),
        "X-M2M-Origin": origin or AE_ORIGINATOR,
        "X-M2M-RVI":    "3",
        "Accept":       "application/json",
    }
    if ty:
        hdrs["Content-Type"] = f"application/json;ty={ty}"
    return hdrs

def http(method, path, body=None, ty=None, origin=None):
    url = f"http://{CSE_HOST}:{HTTP_PORT}{path}"
    hdrs = h(ty=ty, origin=origin)
    r = requests.request(method, url, headers=hdrs,
                         data=json.dumps(body) if body else None, timeout=5)
    return r

def ok(rsc):
    return rsc in (2000, 2001, 4105)   # OK, Created, Conflict (already exists)

def cleanup():
    """Delete the test AE (and its subtree) if it exists."""
    http("DELETE", f"{CSE_BASE}/{AE_NAME}", origin="CAdmin")

# ── Test 1: HTTP ───────────────────────────────────────────────────────────

def test_http():
    print(f"\n{INFO} TEST 1: HTTP (port {HTTP_PORT})")
    passed = True
    cleanup()

    # 1a. CSE-Base GET
    r = http("GET", CSE_BASE, origin="CAdmin")
    ok1 = r.status_code == 200 and r.json().get("m2m:cb", {}).get("ty") == 5
    print(f"  {'PASS' if ok1 else 'FAIL'} GET {CSE_BASE} → {r.status_code}, ty={r.json().get('m2m:cb', {}).get('ty')}")
    passed = passed and ok1

    # 1b. Register AE
    body = {"m2m:ae": {"rn": AE_NAME, "api": "N.test", "aei": AE_ORIGINATOR,
                       "srv": ["3"], "rr": True}}
    r = http("POST", CSE_BASE, body=body, ty=2)
    ok2 = ok(r.json().get("m2m:rsp", {}).get("rsc", r.status_code)) or r.status_code in (201, 409)
    print(f"  {'PASS' if ok2 else 'FAIL'} POST AE → {r.status_code}")
    passed = passed and ok2

    # 1c. Create telemetry container
    body = {"m2m:cnt": {"rn": "telemetry", "mni": 5}}
    r = http("POST", f"{CSE_BASE}/{AE_NAME}", body=body, ty=3)
    ok3 = r.status_code in (201, 409)
    print(f"  {'PASS' if ok3 else 'FAIL'} POST CNT telemetry → {r.status_code}")
    passed = passed and ok3

    # 1d. Send telemetry CIN
    body = {"m2m:cin": {"cnf": "application/json",
                        "con": json.dumps({"lat": 39.933, "lng": -8.892, "seq": 1})}}
    r = http("POST", f"{CSE_BASE}/{AE_NAME}/telemetry", body=body, ty=4)
    ok4 = r.status_code == 201
    print(f"  {'PASS' if ok4 else 'FAIL'} POST CIN telemetry → {r.status_code}")
    passed = passed and ok4

    # 1e. Retrieve CIN
    r = http("GET", f"{CSE_BASE}/{AE_NAME}/telemetry/la", origin="CAdmin")
    ok5 = r.status_code == 200
    if ok5:
        con = json.loads(r.json().get("m2m:cin", {}).get("con", "{}"))
        ok5 = con.get("seq") == 1
    print(f"  {'PASS' if ok5 else 'FAIL'} GET latest CIN, seq={con.get('seq') if ok5 else '?'}")
    passed = passed and ok5

    results["HTTP"] = passed
    print(f"  {PASS if passed else FAIL} HTTP: {'PASSED' if passed else 'FAILED'}")
    return passed

# ── Test 2: WebSocket ──────────────────────────────────────────────────────

async def ws_send_recv(ws, msg, timeout=5.0):
    """Send a OneM2M request and wait for the matching response."""
    rqi = msg["m2m:rqp"]["rqi"]
    await ws.send(json.dumps(msg))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=max(0.1, deadline - time.monotonic()))
        except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
            break
        data = json.loads(raw)
        # Response to our request
        if "m2m:rsp" in data and data["m2m:rsp"].get("rqi") == rqi:
            return data["m2m:rsp"]
        # Notification or verification — ACK it and keep waiting
        if "m2m:rqp" in data and data["m2m:rqp"].get("op") == 5:
            ack = {"m2m:rsp": {"rsc": 2000, "rqi": data["m2m:rqp"]["rqi"],
                               "to": AE_ORIGINATOR, "fr": AE_ORIGINATOR}}
            await ws.send(json.dumps(ack))
    return None

async def test_websocket_async():
    """
    Full OneM2M session over WebSocket:
      1. Connect + Register AE
      2. Create commands container + subscription (nu=aeOriginator — the fix)
      3. POST a command CIN via HTTP (simulating Streamlit)
      4. Verify notification arrives on this WS connection
    """
    print(f"\n{INFO} TEST 2: WebSocket (port {WS_PORT})")
    passed = True
    cleanup()
    uri = f"ws://{CSE_HOST}:{WS_PORT}"

    try:
        async with websockets.connect(uri) as ws:

            def req(op, to, ty, pc):
                return {"m2m:rqp": {"op": op, "to": to, "fr": AE_ORIGINATOR,
                                    "rqi": next_rqi(), "ty": ty, "pc": pc}}

            # 2a. Register AE
            rsp = await ws_send_recv(ws, req(1, CSE_BASE, 2,
                {"m2m:ae": {"rn": AE_NAME, "api": "N.test", "aei": AE_ORIGINATOR,
                            "srv": ["3"], "rr": True}}))
            ok1 = rsp is not None and rsp.get("rsc") in (2001, 4105)
            print(f"  {'PASS' if ok1 else 'FAIL'} WS AE register → rsc={rsp.get('rsc') if rsp else 'timeout'}")
            passed = passed and ok1

            # 2b. Create commands container
            rsp = await ws_send_recv(ws, req(1, f"{CSE_BASE}/{AE_NAME}", 3,
                {"m2m:cnt": {"rn": "commands", "mni": 5}}))
            ok2 = rsp is not None and rsp.get("rsc") in (2001, 4105)
            print(f"  {'PASS' if ok2 else 'FAIL'} WS CNT commands → rsc={rsp.get('rsc') if rsp else 'timeout'}")
            passed = passed and ok2

            # 2c. Create subscription — nu = aeOriginator (the critical fix)
            rsp = await ws_send_recv(ws, req(1, f"{CSE_BASE}/{AE_NAME}/commands", 23,
                {"m2m:sub": {"rn": "sub-cmd", "nu": [AE_ORIGINATOR],
                             "enc": {"net": [3]}, "nct": 2}}))
            ok3 = rsp is not None and rsp.get("rsc") in (2001, 4105)
            print(f"  {'PASS' if ok3 else 'FAIL'} WS SUB (nu={AE_ORIGINATOR}) → rsc={rsp.get('rsc') if rsp else 'timeout'}")
            passed = passed and ok3

            if not passed:
                results["WebSocket"] = False
                return False

            # 2d. POST a command CIN via HTTP (Streamlit simulation)
            #     Do this in a thread to not block the async WS loop
            cin_posted = {"done": False}
            def post_command():
                time.sleep(0.5)   # give WS time to process subscription
                body = {"m2m:cin": {"cnf": "application/json",
                                    "con": json.dumps({"command": "takeoff",
                                                        "seq_cmd": 1,
                                                        "t_cmd_ms": int(time.time()*1000)})}}
                r = http("POST", f"{CSE_BASE}/{AE_NAME}/commands", body=body, ty=4,
                         origin="CAdmin")
                cin_posted["status"] = r.status_code
                cin_posted["done"] = True

            t = threading.Thread(target=post_command, daemon=True)
            t.start()

            # 2e. Wait for the notification on the WS connection
            notif_received = False
            received_command = None
            deadline = time.monotonic() + 8.0
            while time.monotonic() < deadline:
                try:
                    raw = await asyncio.wait_for(
                        ws.recv(), timeout=max(0.1, deadline - time.monotonic()))
                except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
                    break
                data = json.loads(raw)
                # Verification request (vrq=true) — ACK it
                if "m2m:rqp" in data and data["m2m:rqp"].get("op") == 5:
                    rqp = data["m2m:rqp"]
                    pc  = rqp.get("pc", {})
                    sgn = pc.get("m2m:sgn", {})

                    # Send ACK regardless (handles both vrq and real notifications)
                    ack = {"m2m:rsp": {"rsc": 2000, "rqi": rqp["rqi"],
                                       "to": AE_ORIGINATOR, "fr": AE_ORIGINATOR}}
                    await ws.send(json.dumps(ack))

                    if sgn.get("vrq"):
                        print(f"  {INFO} Verification request received (vrq=true) — ACKed")
                        continue

                    # Real notification
                    nev = sgn.get("nev", {})
                    rep = nev.get("rep", {})
                    cin = rep.get("m2m:cin", {})
                    con_raw = cin.get("con", "")
                    if con_raw:
                        try:
                            con = json.loads(con_raw)
                            received_command = con.get("command")
                            notif_received = True
                            break
                        except Exception:
                            pass

            t.join(timeout=2)
            ok4 = notif_received and received_command == "takeoff"
            ok5 = cin_posted.get("status") == 201
            print(f"  {'PASS' if ok5 else 'FAIL'} HTTP POST command CIN → {cin_posted.get('status','?')}")
            print(f"  {'PASS' if ok4 else 'FAIL'} WS notification received, command={received_command!r}")
            passed = passed and ok4 and ok5

    except Exception as e:
        print(f"  {FAIL} WebSocket error: {e}")
        passed = False

    results["WebSocket"] = passed
    print(f"  {PASS if passed else FAIL} WebSocket: {'PASSED' if passed else 'FAILED'}")
    return passed

def test_websocket():
    return asyncio.run(test_websocket_async())

# ── Test 3: MQTT ───────────────────────────────────────────────────────────

def test_mqtt():
    print(f"\n{INFO} TEST 3: MQTT (broker {MQTT_HOST}:{MQTT_PORT})")
    passed = True
    received = {"msgs": [], "connected": False}
    TOPIC_PUB = f"/oneM2M/req/{AE_ORIGINATOR}/{CSE_BASE[1:]}/json"
    TOPIC_SUB = f"/oneM2M/resp/{CSE_BASE[1:]}/{AE_ORIGINATOR}/json"

    def on_connect(client, ud, flags, rc, props=None):
        received["connected"] = (rc == 0)

    def on_message(client, ud, msg):
        received["msgs"].append(msg.payload.decode())

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="test-client")
    client.on_connect = on_connect
    client.on_message = on_message

    try:
        client.connect(MQTT_HOST, MQTT_PORT, 10)
        client.loop_start()
        time.sleep(1)

        ok1 = received["connected"]
        print(f"  {'PASS' if ok1 else 'FAIL'} Connected to Mosquitto broker at {MQTT_HOST}:{MQTT_PORT}")
        passed = passed and ok1

        if ok1:
            client.subscribe(TOPIC_SUB)
            time.sleep(0.3)

            # Publish a OneM2M AE registration request via MQTT
            msg = {"m2m:rqp": {
                "op": 1, "to": CSE_BASE, "fr": AE_ORIGINATOR,
                "rqi": next_rqi(), "ty": 2,
                "pc": {"m2m:ae": {"rn": "mqtt-test-ae", "api": "N.test",
                                   "aei": AE_ORIGINATOR, "srv": ["3"], "rr": False}}
            }}
            client.publish(TOPIC_PUB, json.dumps(msg))
            time.sleep(2)   # wait for CSE to process and respond

            ok2 = len(received["msgs"]) > 0
            if ok2:
                rsp = json.loads(received["msgs"][0])
                rsc = rsp.get("m2m:rsp", {}).get("rsc", 0)
                ok2 = rsc in (2001, 4105, 2000)
                print(f"  {'PASS' if ok2 else 'FAIL'} MQTT request → response rsc={rsc}")
            else:
                print(f"  {FAIL} No MQTT response received within 2s")
            passed = passed and ok2

    except Exception as e:
        print(f"  {FAIL} MQTT error: {e}")
        passed = False
    finally:
        client.loop_stop()
        client.disconnect()

    # Cleanup mqtt-test-ae if it was created
    http("DELETE", f"{CSE_BASE}/mqtt-test-ae", origin="CAdmin")

    results["MQTT"] = passed
    print(f"  {PASS if passed else FAIL} MQTT: {'PASSED' if passed else 'FAILED'}")
    return passed

# ── Test 4: CoAP ───────────────────────────────────────────────────────────

def test_coap():
    """
    CoAP test using aiocoap (installed as part of acmecse deps) or
    a raw UDP probe as fallback.
    """
    print(f"\n{INFO} TEST 4: CoAP (port {COAP_PORT}/udp)")
    passed = False

    # Try aiocoap first
    try:
        import aiocoap
        import aiocoap.resource

        async def coap_get():
            ctx = await aiocoap.Context.create_client_context()
            try:
                req = aiocoap.Message(
                    code=aiocoap.GET,
                    uri=f"coap://{CSE_HOST}:{COAP_PORT}{CSE_BASE}",
                )
                req.opt.content_format = 50  # application/json
                # Add OneM2M options (oneM2M-specific CoAP options: 256-260)
                # Option 256 = X-M2M-Origin, 257 = X-M2M-RI, 261 = X-M2M-RVI
                resp = await asyncio.wait_for(ctx.request(req).response, timeout=5)
                return resp
            finally:
                await ctx.shutdown()

        resp = asyncio.run(coap_get())
        ok1 = resp.code.is_successful()
        print(f"  {'PASS' if ok1 else 'FAIL'} CoAP GET {CSE_BASE} → {resp.code}")
        passed = ok1
    except ImportError:
        # aiocoap not available — raw UDP probe
        import socket
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(2)
            # Minimal CoAP GET: Ver=1, T=0 (CON), Code=0.01 (GET), MID=0x1234
            coap_get = bytes([0x40, 0x01, 0x12, 0x34])
            sock.sendto(coap_get, (CSE_HOST, COAP_PORT))
            data, _ = sock.recvfrom(1024)
            sock.close()
            ok1 = len(data) > 0
            print(f"  {'PASS' if ok1 else 'FAIL'} CoAP UDP probe — {len(data)} bytes received")
            passed = ok1
        except Exception as e:
            print(f"  {FAIL} CoAP UDP probe failed: {e}")
    except Exception as e:
        print(f"  {FAIL} CoAP error: {e}")

    results["CoAP"] = passed
    print(f"  {PASS if passed else FAIL} CoAP: {'PASSED' if passed else 'FAILED'}")
    return passed

# ── Main ───────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 60)
    print("  ACME CSE Protocol Connection Tests")
    print(f"  CSE: http://{CSE_HOST}:{HTTP_PORT}{CSE_BASE}")
    print("=" * 60)

    cleanup()   # ensure clean state
    time.sleep(0.5)

    h_ok  = test_http()
    ws_ok = test_websocket() if HAS_WS else (print(f"\n{FAIL} WebSocket: skipped (no websockets)"), False)[1]
    m_ok  = test_mqtt()
    c_ok  = test_coap()

    cleanup()   # clean up test resources

    print("\n" + "=" * 60)
    print("  RESULTS")
    print("=" * 60)
    for proto, result in results.items():
        print(f"  {PASS if result else FAIL} {proto:<12} {'PASSED' if result else 'FAILED'}")

    all_passed = all(results.values())
    print("=" * 60)
    print(f"  {'ALL PASSED' if all_passed else 'SOME FAILED'}")
    print("=" * 60)
    sys.exit(0 if all_passed else 1)
