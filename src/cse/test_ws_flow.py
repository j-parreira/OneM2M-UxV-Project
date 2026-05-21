"""test_ws_flow.py — Full WebSocket OneM2M flow test for ACME CSE v2025.11"""
import asyncio, websockets, json, requests, time

CSE    = 'http://localhost:8080'
ORIG   = 'Cflowtest1'
AE_RN  = 'flowtest'
_rqi   = 0

def nrqi():
    global _rqi; _rqi += 1; return f'rq{_rqi}'

def hreq(path, method='GET', body=None, ty=None, orig='CAdmin'):
    h = {'X-M2M-RI': nrqi(), 'X-M2M-Origin': orig, 'X-M2M-RVI': '3', 'Accept': 'application/json'}
    if ty:
        h['Content-Type'] = f'application/json;ty={ty}'
    return requests.request(method, f'{CSE}{path}', headers=h,
                            json=body if body else None, timeout=5)

async def send_recv(ws, msg, timeout=6):
    """Send a request and wait for matching response (skip verif. notifications)."""
    rqi = msg['rqi']
    await ws.send(json.dumps(msg))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            raw = json.loads(await asyncio.wait_for(
                ws.recv(), timeout=max(0.2, deadline - time.monotonic())))
        except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
            return None
        if raw.get('op') == 5:  # incoming notification — ACK and keep waiting
            ack = {'rsc': 2000, 'rqi': raw['rqi'], 'to': ORIG, 'fr': ORIG}
            await ws.send(json.dumps(ack))
            continue
        if raw.get('rqi') == rqi:
            return raw
    return None

async def wait_notification(ws, timeout=6):
    """Wait for a real notification (skip verification requests)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            raw = json.loads(await asyncio.wait_for(
                ws.recv(), timeout=max(0.2, deadline - time.monotonic())))
        except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
            return None
        if raw.get('op') == 5:
            sgn = raw.get('pc', {}).get('m2m:sgn', {})
            ack = {'rsc': 2000, 'rqi': raw['rqi'], 'to': ORIG, 'fr': ORIG}
            await ws.send(json.dumps(ack))
            if sgn.get('vrq'):
                print('  [vrq] Verification request — ACKed')
                continue  # keep waiting for real notification
            nev = sgn.get('nev', {})
            rep = nev.get('rep', {})
            cin = rep.get('m2m:cin', {})
            if cin.get('con'):
                return json.loads(cin['con'])
    return None

def req(op, to, ty=None, pc=None):
    r = {'op': op, 'to': to, 'fr': ORIG, 'rqi': nrqi(), 'rvi': '3'}
    if ty is not None: r['ty'] = ty
    if pc is not None: r['pc'] = pc
    return r

async def run():
    # Clean up previous test resources
    hreq(f'/id-in/{AE_RN}', 'DELETE')
    time.sleep(0.2)

    async with websockets.connect('ws://localhost:8180', subprotocols=['oneM2M.json']) as ws:
        print(f'Connected. Subprotocol: {ws.subprotocol}')

        # Step 1: Register AE
        # - No m2m:rqp wrapper (flat JSON)
        # - No aei field (assigned by CSE from originator)
        # - to = /id-in/cse-in (CSE-Base resource path, not just /id-in)
        d = await send_recv(ws, req(1, '/id-in/cse-in', ty=2,
            pc={'m2m:ae': {'rn': AE_RN, 'api': 'N.com.uxv.onem2m', 'srv': ['3'], 'rr': True}}))
        rsc = d.get('rsc') if d else 'timeout'
        aei = (d or {}).get('pc', {}).get('m2m:ae', {}).get('aei')
        ok1 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok1 else "FAIL"} 1. AE register: rsc={rsc}  aei={aei}')

        # Step 2: Create telemetry container
        d = await send_recv(ws, req(1, f'/id-in/{AE_RN}', ty=3,
            pc={'m2m:cnt': {'rn': 'telemetry', 'mni': 10}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok2 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok2 else "FAIL"} 2. CNT telemetry: rsc={rsc}')

        # Step 3: Create commands container
        d = await send_recv(ws, req(1, f'/id-in/{AE_RN}', ty=3,
            pc={'m2m:cnt': {'rn': 'commands', 'mni': 5}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok3 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok3 else "FAIL"} 3. CNT commands: rsc={rsc}')

        # Step 4: Subscribe (nu = aeOriginator — the critical fix)
        d = await send_recv(ws, req(1, f'/id-in/{AE_RN}/commands', ty=23,
            pc={'m2m:sub': {'rn': 'sub-cmd',
                            'nu': [ORIG],
                            'enc': {'net': [3]},
                            'nct': 2}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok4 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok4 else "FAIL"} 4. SUB (nu={ORIG}): rsc={rsc}')

        # Step 5: Create ack container
        d = await send_recv(ws, req(1, f'/id-in/{AE_RN}', ty=3,
            pc={'m2m:cnt': {'rn': 'ack', 'mni': 200}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok5 = rsc in (2001, 4105)
        print(f'  {"PASS" if ok5 else "FAIL"} 5. CNT ack: rsc={rsc}')

        # Step 6: Send telemetry CIN
        tel = json.dumps({'lat': 39.933, 'lng': -8.892, 'alt': 50.0,
                          'isFlying': True, 'seq': 1, 't_send_ms': int(time.time()*1000)})
        d = await send_recv(ws, req(1, f'/id-in/{AE_RN}/telemetry', ty=4,
            pc={'m2m:cin': {'cnf': 'application/json', 'con': tel}}))
        rsc = d.get('rsc') if d else 'timeout'
        ok6 = rsc == 2001
        print(f'  {"PASS" if ok6 else "FAIL"} 6. CIN telemetry: rsc={rsc}')

        # Step 7: Retrieve last telemetry (HTTP) — verify it was stored
        r = hreq(f'/id-in/{AE_RN}/telemetry/la')
        seq = None
        if r.status_code == 200:
            try:
                seq = json.loads(r.json().get('m2m:cin', {}).get('con', '{}')).get('seq')
            except Exception:
                pass
        ok7 = r.status_code == 200 and seq == 1
        print(f'  {"PASS" if ok7 else "FAIL"} 7. GET telemetry/la: status={r.status_code} seq={seq}')

        # Step 8: POST command via HTTP (simulates Streamlit dashboard)
        #   This should trigger the subscription notification on the WebSocket
        time.sleep(0.3)
        cmd = json.dumps({'command': 'takeoff', 'seq_cmd': 1,
                          't_cmd_ms': int(time.time() * 1000)})
        r = hreq(f'/id-in/{AE_RN}/commands', 'POST',
                 {'m2m:cin': {'cnf': 'application/json', 'con': cmd}}, ty=4)
        ok8 = r.status_code == 201
        print(f'  {"PASS" if ok8 else "FAIL"} 8. HTTP POST command: {r.status_code}')

        # Step 9: Wait for notification on WebSocket
        notif = await wait_notification(ws, timeout=8)
        ok9 = notif is not None and notif.get('command') == 'takeoff'
        print(f'  {"PASS" if ok9 else "FAIL"} 9. WS notification: command={notif.get("command") if notif else None}')

        # Summary
        steps = [ok1, ok2, ok3, ok4, ok5, ok6, ok7, ok8, ok9]
        passed = sum(steps)
        print(f'\n  {passed}/{len(steps)} steps passed')
        return all(steps)

    # Cleanup
    hreq(f'/id-in/{AE_RN}', 'DELETE')

result = asyncio.run(run())
exit(0 if result else 1)
