"""
test_http_paths.py — Discover correct HTTP paths for all oneM2M operations
in ACME CSE v2025.11
"""
import requests, json

BASE = 'http://localhost:8080'
rqi  = [0]

def H(orig='CAdmin', ty=None):
    rqi[0] += 1
    h = {'X-M2M-RI': f'r{rqi[0]}', 'X-M2M-Origin': orig,
         'X-M2M-RVI': '3', 'Accept': 'application/json'}
    if ty:
        h['Content-Type'] = f'application/json;ty={ty}'
    return h

# Cleanup
for path in ['/id-in/uxv', '/cse-in/uxv']:
    requests.delete(f'{BASE}{path}', headers=H())

print('=== HTTP AE Registration ===')
# Try /id-in (POST to CSE-ID)
r = requests.post(f'{BASE}/id-in', headers=H(orig='Cuxv1', ty=2),
    json={'m2m:ae': {'rn': 'uxv', 'api': 'N.test', 'srv': ['3'], 'rr': True}})
print(f'POST /id-in: {r.status_code}')
if r.status_code == 201:
    ae = r.json().get('m2m:ae', {})
    print(f'  ri={ae.get("ri")} rn={ae.get("rn")}')
    # Check where it ended up
    for p in ['/id-in/uxv', '/cse-in/uxv', f'/id-in/{ae.get("ri")}']:
        r2 = requests.get(f'{BASE}{p}', headers=H())
        print(f'  GET {p}: {r2.status_code}')
else:
    print(f'  Error: {r.text[:100]}')

print()
print('=== HTTP Container Creation ===')
# Where should the AE be after HTTP registration?
for ae_path in ['/id-in/uxv', '/cse-in/uxv']:
    r = requests.get(f'{BASE}{ae_path}', headers=H())
    if r.status_code == 200:
        print(f'AE found at {ae_path}')
        # Create container under it
        r2 = requests.post(f'{BASE}{ae_path}', headers=H(orig='Cuxv1', ty=3),
            json={'m2m:cnt': {'rn': 'telemetry', 'mni': 10}})
        print(f'  CNT creation at {ae_path}: {r2.status_code}')
        if r2.status_code == 201:
            cnt = r2.json().get('m2m:cnt', {})
            print(f'  CNT ri={cnt.get("ri")} rn={cnt.get("rn")} pi={cnt.get("pi")}')
            # Check CNT path
            for cp in [f'{ae_path}/telemetry', '/cse-in/uxv/telemetry']:
                r3 = requests.get(f'{BASE}{cp}', headers=H())
                print(f'  GET {cp}: {r3.status_code}')
        break
else:
    print('AE not found — checking discovery')
    r = requests.get(f'{BASE}/id-in?fu=1&ty=2', headers=H())
    print(f'Discovery AEs: {r.json()}')

# Cleanup
for path in ['/id-in/uxv', '/cse-in/uxv']:
    requests.delete(f'{BASE}{path}', headers=H())
