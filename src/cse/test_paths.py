"""test_paths.py — discover correct HTTP paths for ACME CSE v2025.11"""
import requests, json

def H(rqi, orig='CAdmin', ty=None):
    h = {'X-M2M-RI': rqi, 'X-M2M-Origin': orig, 'X-M2M-RVI': '3', 'Accept': 'application/json'}
    if ty: h['Content-Type'] = f'application/json;ty={ty}'
    return h

BASE   = 'http://localhost:8080'
AE_RN  = 'flowtest'

# AE at correct path
r = requests.get(f'{BASE}/cse-in/{AE_RN}', headers=H('h1'))
ae = r.json().get('m2m:ae', {})
print(f'AE at /cse-in/{AE_RN}: {r.status_code}  acpi={ae.get("acpi")}  ri={ae.get("ri")}')

# Container creation with AE originator (correct parent path = /cse-in/flowtest)
r = requests.post(f'{BASE}/cse-in/{AE_RN}',
    headers=H('h2', 'Cflowtest1', ty=3),
    json={'m2m:cnt': {'rn': 'telemetry', 'mni': 10}})
print(f'CNT with AE orig (Cflowtest1): {r.status_code}  {r.text[:100]}')

# Container creation with CAdmin
r = requests.post(f'{BASE}/cse-in/{AE_RN}',
    headers=H('h3', 'CAdmin', ty=3),
    json={'m2m:cnt': {'rn': 'telemetry2', 'mni': 10}})
print(f'CNT with CAdmin: {r.status_code}  {r.text[:100]}')

# Discovery
r = requests.get(f'{BASE}/id-in?fu=1', headers=H('h4'))
print(f'Discovery: {r.json().get("m2m:uril", [])[:5]}')

print()
print('KEY FINDINGS:')
print('  HTTP path to AE: /cse-in/{ae-rn}  (NOT /id-in/{ae-rn})')
print('  CSE_BASE for Android oneM2MSession should be /cse-in  (NOT /id-in)')
print('  WS to field for AE registration: "id-in" (CSE-relative ri, no leading /)')
