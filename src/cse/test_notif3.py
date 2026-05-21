"""Ultra-simple notification test — minimal setup"""
import asyncio, websockets, json, requests, time

async def main():
    orig = 'Csimptest'
    ae_path = 'cse-in/simptest'
    H = lambda o,ty=None: {'X-M2M-RI':str(time.time()),'X-M2M-Origin':o,'X-M2M-RVI':'3',
        'Accept':'application/json',**({'Content-Type':f'application/json;ty={ty}'} if ty else {})}

    requests.delete(f'http://localhost:8080/{ae_path}', headers=H('CAdmin'))

    async with websockets.connect('ws://localhost:8180', subprotocols=['oneM2M.json'],
                                   additional_headers={'X-M2M-Origin': orig}) as ws:
        rqi = [0]
        def nrqi(): rqi[0]+=1; return f'r{rqi[0]}'

        # Use recv task to collect ALL WS messages
        all_msgs = []

        async def recv_task():
            while True:
                try:
                    d = json.loads(await ws.recv())
                    all_msgs.append(d)
                    if d.get('op') == 5:
                        ack = {'rsc':2000,'rqi':d['rqi'],'to':orig,'fr':orig}
                        await ws.send(json.dumps(ack))
                except Exception as e:
                    break

        # Start background receiver
        receiver = asyncio.create_task(recv_task())

        async def send(r, t=5):
            await ws.send(json.dumps(r))
            await asyncio.sleep(0.5)
            deadline = time.monotonic() + t
            while time.monotonic() < deadline:
                for m in all_msgs:
                    if m.get('rqi') == r['rqi'] and 'rsc' in m:
                        return m
                await asyncio.sleep(0.1)
            return None

        # Minimal setup: AE + CNT + SUB
        d = await send({'op':1,'to':'id-in','fr':orig,'rqi':nrqi(),'rvi':'3','ty':2,
                        'pc':{'m2m:ae':{'rn':'simptest','api':'N.t','srv':['3'],'rr':True}}})
        print(f'AE: rsc={d.get("rsc") if d else "timeout"}')

        d = await send({'op':1,'to':ae_path,'fr':orig,'rqi':nrqi(),'rvi':'3','ty':3,
                        'pc':{'m2m:cnt':{'rn':'commands','mni':5}}})
        print(f'CNT: rsc={d.get("rsc") if d else "timeout"}')

        d = await send({'op':1,'to':f'{ae_path}/commands','fr':orig,'rqi':nrqi(),'rvi':'3','ty':23,
                        'pc':{'m2m:sub':{'rn':'sub-c','nu':[orig],'enc':{'net':[3]}}}})
        print(f'SUB: rsc={d.get("rsc") if d else "timeout"}')

        await asyncio.sleep(1)  # wait for verification request if any

        print(f'\nAll WS messages so far: {len(all_msgs)}')
        for m in all_msgs:
            print(f'  op={m.get("op")} rsc={m.get("rsc")} rqi={m.get("rqi")} '
                  f'has_sgn={bool(m.get("pc",{}).get("m2m:sgn"))}')

        # Post command
        cmd = json.dumps({'command':'takeoff','seq_cmd':1,'t_cmd_ms':int(time.time()*1000)})
        r = requests.post(f'http://localhost:8080/{ae_path}/commands',
            headers=H('CAdmin',ty=4), json={'m2m:cin':{'con':cmd}})
        print(f'\nHTTP POST cmd: {r.status_code}')

        print('Waiting 15s for notification...')
        deadline = time.monotonic() + 15
        initial_count = len(all_msgs)
        while time.monotonic() < deadline:
            if len(all_msgs) > initial_count:
                new_msgs = all_msgs[initial_count:]
                print(f'New messages ({len(new_msgs)}):')
                for m in new_msgs:
                    print(f'  op={m.get("op")} rsc={m.get("rsc")} '
                          f'sgn={bool(m.get("pc",{}).get("m2m:sgn"))}')
                    if m.get('op') == 5:
                        sgn = m.get('pc',{}).get('m2m:sgn',{})
                        nev = sgn.get('nev',{})
                        rep = nev.get('rep',{})
                        con = rep.get('m2m:cin',{}).get('con','')
                        print(f'  -> NOTIFICATION! con={con[:80]}')
                break
            await asyncio.sleep(0.5)
        else:
            print('No new messages in 15s')

        receiver.cancel()

    requests.delete(f'http://localhost:8080/{ae_path}', headers=H('CAdmin'))

asyncio.run(main())
