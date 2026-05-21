"""Debug WebSocket notification delivery"""
import asyncio, websockets, json, requests, time

async def main():
    orig = 'Cnotiftest'
    ae_path = 'cse-in/notiftest'

    async with websockets.connect('ws://localhost:8180', subprotocols=['oneM2M.json'],
                                   additional_headers={'X-M2M-Origin': orig}) as ws:
        rqi = [0]
        def nrqi(): rqi[0]+=1; return f'r{rqi[0]}'

        async def send(r, timeout=5):
            await ws.send(json.dumps(r))
            while True:
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
                print(f'  RAW recv: keys={list(d.keys())[:5]} op={d.get("op")} rsc={d.get("rsc")} rqi={d.get("rqi")}')
                if d.get('op') == 5:
                    sgn = d.get('pc', {}).get('m2m:sgn', {})
                    print(f'    NOTIFY: vrq={sgn.get("vrq")} has_nev={bool(sgn.get("nev"))}')
                    ack = {'rsc': 2000, 'rqi': d['rqi'], 'to': orig, 'fr': orig}
                    await ws.send(json.dumps(ack))
                    continue
                return d

        # Full setup
        for req in [
            {'op':1,'to':'id-in','fr':orig,'rqi':nrqi(),'rvi':'3','ty':2,
             'pc':{'m2m:ae':{'rn':'notiftest','api':'N.t','srv':['3'],'rr':True}}},
            {'op':1,'to':ae_path,'fr':orig,'rqi':nrqi(),'rvi':'3','ty':3,
             'pc':{'m2m:cnt':{'rn':'commands','mni':5}}},
        ]:
            d = await send(req)
            print(f'Setup rsc={d.get("rsc")}')

        # Create subscription
        sub = {'op':1,'to':f'{ae_path}/commands','fr':orig,'rqi':nrqi(),'rvi':'3','ty':23,
               'pc':{'m2m:sub':{'rn':'sub-c','nu':[orig],'enc':{'net':[3]}}}}
        print('\nCreating subscription...')
        d = await send(sub, timeout=8)
        print(f'SUB rsc={d.get("rsc")}')

        # Wait a bit for any pending verification
        print('\nWaiting 1s for pending WS messages...')
        try:
            while True:
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=1.0))
                print(f'  Pending: op={d.get("op")} rsc={d.get("rsc")}')
                if d.get('op') == 5:
                    ack = {'rsc': 2000, 'rqi': d['rqi'], 'to': orig, 'fr': orig}
                    await ws.send(json.dumps(ack))
        except asyncio.TimeoutError:
            print('  (no more pending messages)')

        # Post command and watch for ALL messages
        print('\nPosting command via HTTP...')
        cmd = json.dumps({'command': 'takeoff', 'seq_cmd': 1,
                          't_cmd_ms': int(time.time()*1000)})
        r = requests.post(f'http://localhost:8080/{ae_path}/commands',
            headers={'X-M2M-RI':nrqi(),'X-M2M-Origin':'CAdmin','X-M2M-RVI':'3',
                     'Content-Type':'application/json;ty=4','Accept':'application/json'},
            json={'m2m:cin': {'con': cmd}})
        print(f'HTTP POST: {r.status_code}')

        print('\nWaiting 10s for notification...')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=1.0))
                print(f'  WS recv: {json.dumps(d, indent=2)[:300]}')
                if d.get('op') == 5:
                    ack = {'rsc': 2000, 'rqi': d['rqi'], 'to': orig, 'fr': orig}
                    await ws.send(json.dumps(ack))
            except asyncio.TimeoutError:
                pass

    requests.delete(f'http://localhost:8080/{ae_path}',
                    headers={'X-M2M-RI':nrqi(),'X-M2M-Origin':'CAdmin','X-M2M-RVI':'3'})

asyncio.run(main())
