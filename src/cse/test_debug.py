"""Debug subscription and CIN creation in ACME CSE v2025.11"""
import asyncio, websockets, json, requests, time

async def main():
    orig = 'Cdebug1'
    ae_path = 'cse-in/debug1'

    async with websockets.connect('ws://localhost:8180', subprotocols=['oneM2M.json'],
                                   additional_headers={'X-M2M-Origin': orig}) as ws:
        rqi = [0]
        def nrqi(): rqi[0]+=1; return f'r{rqi[0]}'

        async def send(r):
            await ws.send(json.dumps(r))
            while True:
                d = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
                if d.get('op') == 5:
                    ack = {'rsc': 2000, 'rqi': d['rqi'], 'to': orig, 'fr': orig}
                    await ws.send(json.dumps(ack))
                    continue
                return d

        # Setup
        d = await send({'op':1,'to':'id-in','fr':orig,'rqi':nrqi(),'rvi':'3','ty':2,
                        'pc':{'m2m:ae':{'rn':'debug1','api':'N.t','srv':['3'],'rr':True}}})
        print(f'AE: rsc={d.get("rsc")}')

        d = await send({'op':1,'to':ae_path,'fr':orig,'rqi':nrqi(),'rvi':'3','ty':3,
                        'pc':{'m2m:cnt':{'rn':'commands','mni':5}}})
        print(f'CNT commands: rsc={d.get("rsc")}')

        # Debug subscription
        sub_req = {'op':1,'to':f'{ae_path}/commands','fr':orig,'rqi':nrqi(),'rvi':'3','ty':23,
                   'pc':{'m2m:sub':{'rn':'sub-cmd','nu':[orig],'enc':{'net':[3]},'nct':2}}}
        print(f'\nSUB request to={sub_req["to"]}')
        d = await send(sub_req)
        print(f'SUB rsc={d.get("rsc")} dbg={d.get("pc",{}).get("m2m:dbg","")}')

        # Debug CIN in telemetry
        d = await send({'op':1,'to':ae_path,'fr':orig,'rqi':nrqi(),'rvi':'3','ty':3,
                        'pc':{'m2m:cnt':{'rn':'telemetry','mni':10}}})
        print(f'\nCNT telemetry: rsc={d.get("rsc")}')

        cin_con = json.dumps({'lat': 39.9, 'seq': 1})
        cin_req = {'op':1,'to':f'{ae_path}/telemetry','fr':orig,'rqi':nrqi(),'rvi':'3','ty':4,
                   'pc':{'m2m:cin':{'cnf':'application/json','con':cin_con}}}
        print(f'CIN request to={cin_req["to"]}')
        d = await send(cin_req)
        print(f'CIN rsc={d.get("rsc")} dbg={d.get("pc",{}).get("m2m:dbg","")}')

        # HTTP POST debug
        r = requests.post('http://localhost:8080/cse-in/debug1/commands',
            headers={'X-M2M-RI':'h1','X-M2M-Origin':'CAdmin','X-M2M-RVI':'3',
                     'Content-Type':'application/json;ty=4','Accept':'application/json'},
            json={'m2m:cin':{'cnf':'application/json','con':json.dumps({'command':'test'})}})
        print(f'\nHTTP POST command: {r.status_code} {r.text[:100]}')

    requests.delete('http://localhost:8080/cse-in/debug1',
                    headers={'X-M2M-RI':'cl','X-M2M-Origin':'CAdmin','X-M2M-RVI':'3'})

asyncio.run(main())
