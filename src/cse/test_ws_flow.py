"""
test_ws_flow.py — Full WebSocket OneM2M flow test for ACME CSE v2025.11

Validates the exact request/response format used by the Android app's OneM2MSession
after the v2025.11 fixes:
  - Flat JSON (no m2m:rqp/m2m:rsp wrappers)
  - to="id-in" for AE registration (CSE-relative CSE-ID)
  - to="cse-in/..." for all other resources (CSE-Base resource name prefix)
  - rvi="3" mandatory in all requests
  - No "aei" in AE registration body (non-provision attribute)
  - nu=aeOriginator for subscriptions (not AE resource URI)
  - Flat ACK: {"rsc":2000,...} without m2m:rsp wrapper

Run: docker cp test_ws_flow.py acme-cse:/test_ws_flow.py && docker exec acme-cse python3 /test_ws_flow.py
"""
import asyncio, websockets, json, requests, time

HTTP   = 'http://localhost:8080'
WS_URI = 'ws://localhost:8180'
ORIG   = 'Cflowtest1'   # must start with C
AE_RN  = 'flowtest'

# Correct paths per ACME CSE v2025.11:
CSE_ID   = 'id-in'        # for AE registration target (to field)
CSE_BASE = 'cse-in'       # CSE-Base resource name — prefix for all child resources
AE_PATH  = f'{CSE_BASE}/{AE_RN}'         # = "cse-in/flowtest"

_rqi = [0]
def nrqi():
    _rqi[0] += 1; return f'rq{_rqi[0]}'

def H(orig='CAdmin', ty=None):
    h = {'X-M2M-RI': nrqi(), 'X-M2M-Origin': orig, 'X-M2M-RVI': '3', 'Accept': 'application/json'}
    if ty: h['Content-Type'] = f'application/json;ty={ty}'
    return h

async def send_recv(ws, msg, timeout=6):
    """Send a flat request and wait for matching response (skip verification notifs)."""
    rqi = msg['rqi']
    await ws.send(json.dumps(msg))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            raw = json.loads(await asyncio.wait_for(
                ws.recv(), timeout=max(0.2, deadline - time.monotonic())))
        except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
            return None
        # Incoming notification — ACK and keep waiting for our response
        if raw.get('op') == 5:
            ack = {'rsc': 2000, 'rqi': raw['rqi'], 'to': ORIG, 'fr': ORIG}
            await ws.send(json.dumps(ack))
            continue
        if raw.get('rqi') == rqi:
            return raw
    return None

async def wait_notification(ws, timeout=8):
    """Wait for a real command notification (skip verification requests)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            raw = json.loads(await asyncio.wait_for(
                ws.recv(), timeout=max(0.2, deadline - time.monotonic())))
        except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
            return None
        if raw.get('op') == 5:
            sgn = raw.get('pc', {}).get('m2m:sgn', {})
            # Flat ACK — no m2m:rsp wrapper
            ack = {'rsc': 2000, 'rqi': raw['rqi'], 'to': ORIG, 'fr': ORIG}
            await ws.send(json.dumps(ack))
            if sgn.get('vrq'):
                print('  [vrq] Verification request — ACKed (flat format)')
                continue
            nev = sgn.get('nev', {})
            con_raw = nev.get('rep', {}).get('m2m:cin', {}).get('con', '')
            if con_raw:
                return json.loads(con_raw)
    return None

def flat_req(op, to, ty=None, pc=None):
    """Build a flat OneM2M request (no m2m:rqp wrapper, rvi mandatory)."""
    r = {'op': op, 'to': to, 'fr': ORIG, 'rqi': nrqi(), 'rvi': '3'}
    if ty is not None: r['ty'] = ty
    if pc is not None: r['pc'] = pc
    return r

async def run():
    # Clean up previous test resources (HTTP, CAdmin)
    requests.delete(f'{HTTP}/{CSE_BASE}/{AE_RN}',
                    headers={'X-M2M-RI': nrqi(), 'X-M2M-Origin': 'CAdmin', 'X-M2M-RVI': '3'})
    time.sleep(0.2)

    # Pass X-M2M-Origin in WebSocket upgrade headers — required by ACME CSE v2025.11
    # The CSE associates the connection with this originator for access control.
    async with websockets.connect(WS_URI, subprotocols=['oneM2M.json'],
                                   additional_headers={'X-M2M-Origin': ORIG}) as ws:
        print(f'Connected. Subprotocol: {ws.subprotocol}')

        # Step 1: Register AE
        # - to = CSE_ID ("id-in") — CSE-relative, no leading slash
        # - No aei field — non-provision attribute in v2025.11
        # - poa REQUIRED: CSE uses this to route notifications; existing WS connection is reused
        d = await send_recv(ws, flat_req(1, CSE_ID, ty=2,
            pc={'m2m:ae': {'rn': AE_RN, 'api': 'N.com.uxv.onem2m', 'srv': ['3'], 'rr': True,
                           'poa': ['ws://localhost:8180']}}))
        rsc = d.get('rsc') if d else 'timeout'
        aei = (d or {}).get('pc', {}).get('m2m:ae', {}).get('aei')
        ok1 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok1 else "FAIL"} 1. AE register: rsc={rsc} aei={aei}')

        # Step 2: Create telemetry container
        # - to = "cse-in/flowtest" (CSE-Base rn + AE rn)
        d = await send_recv(ws, flat_req(1, AE_PATH, ty=3,
            pc={'m2m:cnt': {'rn': 'telemetry', 'mni': 10}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok2 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok2 else "FAIL"} 2. CNT telemetry: rsc={rsc}')

        # Step 3: Create commands container
        d = await send_recv(ws, flat_req(1, AE_PATH, ty=3,
            pc={'m2m:cnt': {'rn': 'commands', 'mni': 5}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok3 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok3 else "FAIL"} 3. CNT commands: rsc={rsc}')

        # Step 4: Subscribe — nu = aeOriginator (NOT AE resource URI)
        # nct omitted — nct=2 + net=[3] is invalid in ACME CSE v2025.11
        d = await send_recv(ws, flat_req(1, f'{AE_PATH}/commands', ty=23,
            pc={'m2m:sub': {'rn': 'sub-cmd',
                            'nu': [ORIG],              # aeOriginator — WS connection lookup
                            'enc': {'net': [3]}}}))    # no nct — use CSE default
        rsc = d.get('rsc') if d else 'timeout'
        ok4 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok4 else "FAIL"} 4. SUB (nu={ORIG}): rsc={rsc}')

        # Step 5: Create ack container
        d = await send_recv(ws, flat_req(1, AE_PATH, ty=3,
            pc={'m2m:cnt': {'rn': 'ack', 'mni': 200}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok5 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok5 else "FAIL"} 5. CNT ack: rsc={rsc}')

        # Step 6: Send telemetry CIN (fire-and-forget)
        # cnf omitted — 'application/json' fails validation in ACME CSE v2025.11
        tel = json.dumps({'lat': 39.933, 'lng': -8.892, 'alt': 50.0,
                          'isFlying': True, 'seq': 1,
                          't_send_ms': int(time.time() * 1000)})
        d = await send_recv(ws, flat_req(1, f'{AE_PATH}/telemetry', ty=4,
            pc={'m2m:cin': {'con': tel}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok6 = rsc == 2001
        print(f'  {"PASS" if ok6 else "FAIL"} 6. CIN telemetry: rsc={rsc}')

        # Step 7: Retrieve last telemetry via HTTP — verify stored
        r = requests.get(f'{HTTP}/{CSE_BASE}/{AE_RN}/telemetry/la',
                         headers=H(orig='CAdmin'))
        seq = None
        if r.status_code == 200:
            try:
                seq = json.loads(r.json().get('m2m:cin', {}).get('con', '{}')).get('seq')
            except Exception:
                pass
        ok7 = r.status_code == 200 and seq == 1
        print(f'  {"PASS" if ok7 else "FAIL"} 7. GET telemetry/la: {r.status_code} seq={seq}')

        # Step 8: POST command via HTTP (simulates Streamlit dashboard)
        # cnf omitted in HTTP POST too
        time.sleep(0.3)
        cmd = json.dumps({'command': 'takeoff', 'seq_cmd': 1,
                          't_cmd_ms': int(time.time() * 1000)})
        r = requests.post(f'{HTTP}/{CSE_BASE}/{AE_RN}/commands',
                          headers=H(orig='CAdmin', ty=4),
                          json={'m2m:cin': {'con': cmd}})
        ok8 = r.status_code == 201
        print(f'  {"PASS" if ok8 else "FAIL"} 8. HTTP POST command: {r.status_code}')

        # Step 9: Wait for WS notification — validates nu=aeOriginator fix
        notif = await wait_notification(ws, timeout=10)
        ok9 = notif is not None and notif.get('command') == 'takeoff'
        print(f'  {"PASS" if ok9 else "FAIL"} 9. WS notification: command={notif.get("command") if notif else None}')

        steps = [ok1, ok2, ok3, ok4, ok5, ok6, ok7, ok8, ok9]
        passed = sum(steps)
        print(f'\n  {passed}/{len(steps)} steps passed')
        return all(steps)

    # Cleanup
    requests.delete(f'{HTTP}/{CSE_BASE}/{AE_RN}',
                    headers={'X-M2M-RI': nrqi(), 'X-M2M-Origin': 'CAdmin', 'X-M2M-RVI': '3'})

result = asyncio.run(run())
exit(0 if result else 1)
