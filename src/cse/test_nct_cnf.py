"""Test correct nct and cnf values for ACME CSE v2025.11"""
import asyncio, websockets, json, requests

async def main():
    orig = 'Cfix1'
    ae_path = 'cse-in/fixtest'

    async with websockets.connect('ws://localhost:8180', subprotocols=['oneM2M.json'],
                                   additional_headers={'X-M2M-Origin': orig}) as ws:
        rqi = [0]
        def nrqi(): rqi[0]+=1; return f'r{rqi[0]}'

        async def send(r):
            await ws.send(json.dumps(r))
            while True:
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=4))
                if d.get('op') == 5:
                    await ws.send(json.dumps({'rsc':2000,'rqi':d['rqi'],'to':orig,'fr':orig}))
                    continue
                return d

        # Setup
        for ty, rn, pc in [
            (2, None, {'m2m:ae': {'rn':'fixtest','api':'N.t','srv':['3'],'rr':True}}),
            (3, None, {'m2m:cnt':{'rn':'commands','mni':5}}),
            (3, None, {'m2m:cnt':{'rn':'telemetry','mni':10}}),
        ]:
            target = 'id-in' if ty==2 else ae_path
            d = await send({'op':1,'to':target,'fr':orig,'rqi':nrqi(),'rvi':'3','ty':ty,'pc':pc})
            print(f'Setup {list(pc.keys())[0]}: rsc={d.get("rsc")}')

        print('\n=== SUB nct values ===')
        for nct_val in [None, 1, 3, 4]:
            sub_pc = {'rn': f'sub-{nct_val}', 'nu': [orig], 'enc': {'net': [3]}}
            if nct_val is not None: sub_pc['nct'] = nct_val
            d = await send({'op':1,'to':f'{ae_path}/commands','fr':orig,
                            'rqi':nrqi(),'rvi':'3','ty':23,'pc':{'m2m:sub':sub_pc}})
            rsc = d.get('rsc')
            dbg = d.get('pc',{}).get('m2m:dbg','')[:80]
            print(f'  nct={str(nct_val):<6} rsc={rsc}  {dbg}')
            if rsc in (2001, 4105):
                print(f'  -> VALID nct value: {nct_val}')
                break

        print('\n=== CIN cnf values ===')
        for cnf in [None, 'application/json:0', '1:0', 'application/vnd.onem2m-res+json',
                    'text/plain', 'application/cbor']:
            cin_pc = {'con': json.dumps({'seq': 1})}
            if cnf is not None: cin_pc['cnf'] = cnf
            d = await send({'op':1,'to':f'{ae_path}/telemetry','fr':orig,
                            'rqi':nrqi(),'rvi':'3','ty':4,'pc':{'m2m:cin':cin_pc}})
            rsc = d.get('rsc')
            dbg = d.get('pc',{}).get('m2m:dbg','')[:80]
            print(f'  cnf={str(cnf):<40} rsc={rsc}  {dbg}')
            if rsc == 2001:
                print(f'  -> VALID cnf value: {cnf!r}')
                break

    requests.delete('http://localhost:8080/cse-in/fixtest',
                    headers={'X-M2M-RI':'cl','X-M2M-Origin':'CAdmin','X-M2M-RVI':'3'})

asyncio.run(main())
