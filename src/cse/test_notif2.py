"""Test notification with subscription inspection"""
import asyncio, websockets, json, requests, time

async def main():
    orig = 'Cnotiftest2'
    ae_path = 'cse-in/notif2'
    H = lambda o='CAdmin': {'X-M2M-RI':f'h{time.time()}','X-M2M-Origin':o,'X-M2M-RVI':'3','Accept':'application/json'}

    # Cleanup
    requests.delete(f'http://localhost:8080/{ae_path}', headers=H())

    async with websockets.connect('ws://localhost:8180', subprotocols=['oneM2M.json'],
                                   additional_headers={'X-M2M-Origin': orig}) as ws:
        rqi = [0]
        def nrqi(): rqi[0]+=1; return f'r{rqi[0]}'

        async def send(r, t=5):
            await ws.send(json.dumps(r))
            d = json.loads(await asyncio.wait_for(ws.recv(), timeout=t))
            if d.get('op') == 5:
                print(f'  NOTIFY during send: {json.dumps(d)[:100]}')
                await ws.send(json.dumps({'rsc':2000,'rqi':d['rqi'],'to':orig,'fr':orig}))
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=t))
            return d

        # Register AE + commands container
        d = await send({'op':1,'to':'id-in','fr':orig,'rqi':nrqi(),'rvi':'3','ty':2,
                        'pc':{'m2m:ae':{'rn':'notif2','api':'N.t','srv':['3'],'rr':True}}})
        print(f'AE: rsc={d.get("rsc")} aei={d.get("pc",{}).get("m2m:ae",{}).get("aei")}')

        d = await send({'op':1,'to':ae_path,'fr':orig,'rqi':nrqi(),'rvi':'3','ty':3,
                        'pc':{'m2m:cnt':{'rn':'commands','mni':5}}})
        print(f'CNT: rsc={d.get("rsc")}')

        # Create subscription and inspect stored nu
        d = await send({'op':1,'to':f'{ae_path}/commands','fr':orig,'rqi':nrqi(),'rvi':'3','ty':23,
                        'pc':{'m2m:sub':{'rn':'sub-c','nu':[orig],'enc':{'net':[3]}}}})
        print(f'SUB: rsc={d.get("rsc")}')

        # Check what nu was stored
        r = requests.get(f'http://localhost:8080/{ae_path}/commands/sub-c', headers=H())
        if r.status_code == 200:
            sub = r.json().get('m2m:sub', {})
            print(f'Stored SUB nu: {sub.get("nu")}')
            print(f'Stored SUB enc: {sub.get("enc")}')
        else:
            print(f'SUB GET: {r.status_code}')

        # Check AE poa
        r = requests.get(f'http://localhost:8080/{ae_path}', headers=H())
        ae = r.json().get('m2m:ae', {})
        print(f'AE poa: {ae.get("poa")}')

        # Wait briefly then post command
        time.sleep(0.5)
        cmd = json.dumps({'command': 'takeoff', 'seq_cmd': 1, 't_cmd_ms': int(time.time()*1000)})
        r = requests.post(f'http://localhost:8080/{ae_path}/commands',
            headers={**H('CAdmin'), 'Content-Type': 'application/json;ty=4'},
            json={'m2m:cin': {'con': cmd}})
        print(f'HTTP POST cmd: {r.status_code}')

        # Watch for notification
        print('Watching WS for 8s...')
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=0.5))
                print(f'WS msg: op={d.get("op")} rsc={d.get("rsc")} keys={list(d.keys())}')
                if d.get('op') == 5:
                    sgn = d.get('pc', {}).get('m2m:sgn', {})
                    print(f'  NOTIFICATION! vrq={sgn.get("vrq")} con={sgn.get("nev",{}).get("rep",{}).get("m2m:cin",{}).get("con","")}')
                    await ws.send(json.dumps({'rsc':2000,'rqi':d['rqi'],'to':orig,'fr':orig}))
            except asyncio.TimeoutError:
                pass

    requests.delete(f'http://localhost:8080/{ae_path}', headers=H())

asyncio.run(main())
