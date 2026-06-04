# CLAUDE.md — Streamlit Dashboard (`src/frontend/`)

> Guia de arquitectura, convenções e integração para o frontend do benchmark OneM2M.
> Lê este ficheiro antes de tocar em qualquer coisa em `src/frontend/`.
> Complementa `docs/ai-context/frontend-dev.md` e `docs/ai-context/project-context.md`.

---

## Stack e Ambiente

```
src/frontend/
├── app.py                  ← entry point: python -m streamlit run app.py
├── pages/
│   ├── 1_manual.py         ← controlo manual + telemetria em directo
│   ├── 2_benchmark.py      ← orquestrador single-run por clique; S3 sempre ping-only; 10 runs × 1 min por cenário
│   └── 3_results.py        ← visualização rápida do último run
├── core/
│   ├── config.py           ← Config dataclass; carrega .env com python-dotenv
│   ├── logger.py           ← MetricRecord + save_run() → data/raw/
│   ├── orchestrator.py     ← RunConfig + run_scenario_1/2/3(); background thread
│   └── protocols/
│       ├── base.py         ← ProtocolClient (ABC)
│       ├── websocket_client.py
│       ├── mqtt_client.py
│       ├── http_client.py
│       └── coap_client.py
├── requirements.txt        ← dependências pinadas (ver abaixo)
└── .env.example            ← template; copiar para .env (git-ignored)
```

**Activar o ambiente antes de qualquer comando Python:**

```bash
# Windows (PowerShell)
.venv\Scripts\Activate.ps1
# Linux / WSL / Git Bash
source .venv/bin/activate
```

**Iniciar a app:**

```bash
python -m streamlit run app.py
```

---

## Configuração

Todos os parâmetros vêm de variáveis de ambiente (ou ficheiro `.env`). Nunca hardcoded.
Copiar `.env.example` para `.env` e preencher com os IPs reais da LAN.

| Variável | Default | Notas |
|---|---|---|
| `CSE_HOST` | `127.0.0.1` | IP LAN da máquina a correr `docker compose up` |
| `CSE_HTTP_PORT` | `8080` | HTTP binding do ACME CSE |
| `CSE_MQTT_PORT` | `1883` | Broker Mosquitto |
| `CSE_WS_PORT` | `8180` | WebSocket binding do ACME CSE |
| `CSE_COAP_PORT` | `5683` | CoAP binding do ACME CSE (UDP) |
| `DATA_RAW_DIR` | `../../data/raw` | Relativo a `src/frontend/` |
| `CALLBACK_HOST` | `127.0.0.1` | **IP LAN desta máquina** (não 127.0.0.1 em LAN real — Docker não alcança loopback). Usado apenas para bind local. |
| `CALLBACK_HTTP_PORT` | `8090` | Servidor HTTP embutido para notificações (HTTP e CoAP transport) |
| `CALLBACK_COAP_PORT` | `5684` | Porta CoAP (reservada; não usada como `nu` — Docker Desktop bloqueia UDP de containers) |
| `DOCKER_CALLBACK_HOST` | `host.docker.internal` | Hostname desta máquina **visto de dentro do container**. Docker Desktop roteia TCP via este hostname. Usado nas URLs `nu` de subscriptions HTTP e CoAP. **Não alterar** sem perceber o networking Docker Desktop. |
| `MQTT_BROKER_POA_HOST` | `mosquitto` | Hostname do broker MQTT **visto de dentro do container CSE** (nome do serviço Docker Compose). Usado no campo `poa` do AE do Streamlit. |

---

## Originators OneM2M

| Transport | Originator | AE registration? |
|---|---|---|
| WebSocket | `CStreamlit` | Sim — `poa=["ws://host:8180"]` |
| MQTT | `CStreamlit` | Sim — `poa=["mqtt://host:1883"]` |
| HTTP | `CAdmin` | Não — CAdmin é admin, não precisa de registo |
| CoAP | `CAdmin` | Não — idem |

---

## Tópicos MQTT (CStreamlit)

| Tópico | Direcção | Descrição |
|---|---|---|
| `/oneM2M/req/CStreamlit/id-in/json` | Streamlit → CSE | Requests (create CIN, subscribe, etc.) |
| `/oneM2M/resp/CStreamlit/id-in/json` | CSE → Streamlit | Respostas aos requests |
| `/oneM2M/req/id-in/CStreamlit/json` | CSE → Streamlit | Notificações push (SUB deliveries) |

> **Atenção:** request/response têm ordem `{originator}/{cseID}`; notificações invertem para `{cseID}/{originator}`.

---

## Árvore de Recursos OneM2M

```
/id-in                        ← CSE-Base (GET /id-in para health check)
/cse-in/uxv                   ← AE (criado pela app Android)
/cse-in/uxv/telemetry         ← CNT mni=10 — Streamlit subscreve (Cenário 1)
/cse-in/uxv/commands          ← CNT mni=5  — Streamlit envia comandos aqui (Cenário 2)
/cse-in/uxv/commands/sub-commands  ← SUB — notifica a app Android
/cse-in/uxv/ack               ← CNT mni=200 — Streamlit subscreve ACKs (Cenário 2)
```

> **Nota de startup:** Streamlit deve iniciar **após** a app Android se registar.
> `_ensure_subscription()` falha se os containers (`telemetry`, `ack`) ainda não existirem.

---

## Interface ProtocolClient

Todos os clientes implementam a mesma ABC (`core/protocols/base.py`):

```python
class ProtocolClient(ABC):
    def connect(self) -> None: ...
    def send_command(self, payload: dict) -> tuple[float, bool]: ...
    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None: ...
    def subscribe_ack(self, callback: Callable[[dict], None]) -> None: ...
    def disconnect(self) -> None: ...
    def get_header_bytes(self, payload: dict) -> int: ...
```

O orquestrador é protocol-agnostic — só instancia a classe certa conforme `RunConfig.protocol`.

---

## Métricas (MetricRecord)

Um `MetricRecord` por mensagem → escrito em CSV em `data/raw/`.

| Campo | Tipo | NTP-dep? | Notas |
|---|---|---|---|
| `latency_ms` | float\|None | **Sim** | S1: `timestamp_ms − t_send_ms`; S2: `t_recv_ms − t_cmd_ms` (dois relógios: dev + RC) |
| `cin_create_ms` | float\|None | Não | S2: RTT Streamlit→CSE (monotónico, mesmo dispositivo) |
| `payload_bytes` | int | — | Bytes no campo `con` do CIN |
| `header_bytes` | int | — | Overhead do protocolo |
| `delivered` | bool | — | True se ACK recebido dentro do timeout |
| `seq` | int | — | Contador monotónico do Android; gaps = packet loss |

---

## Problemas Conhecidos

| Problema | Estado |
|---|---|
| CoAP não conseguia ligar no Windows ("transport can not be bound to any-address") | **Corrigido (2026-05-31)**: bind em `callback_host` (LAN IP) em vez de `0.0.0.0`; aiocoap no Windows rejeita `0.0.0.0`. Agora irrelevante — aiocoap já não faz bind de servidor. |
| CoAP requests sem opções oneM2M → RSC=4000 do CSE | **Corrigido (2026-05-31)**: FR/RQI/RVI/TY em opções 279/283/271/267; RSC lido de opção 307 |
| Segunda tentativa de ligação CoAP falhava com porta já em uso | **Corrigido (2026-05-31)**: `connect()` chama `disconnect()` primeiro; `disconnect()` limpa todas as referências |
| Docker Desktop bloqueia UDP de containers → CSE não consegue entregar notificações CoAP (Streamlit) | **Corrigido (2026-05-31)**: `coap_client.py` usa HTTP callback server + `nu=http://host.docker.internal:8090/notify`. Outgoing requests continuam em CoAP UDP. Documentar no paper como constraint de laboratório. |
| Docker Desktop bloqueia UDP de containers → CSE não consegue entregar notificações CoAP (Android RC) | **Corrigido (2026-05-31)**: `CoApProtocolClient.java` usa NanoHTTPD (porta 8182) em vez de Californium CoapServer (UDP). `poa=["http://rc_ip:8182"]`. Mesmo constraint — ambas as pontas usam HTTP para notificações. |
| HTTP/CoAP callbacks com `CALLBACK_HOST=127.0.0.1` inacessíveis ao CSE container | **Corrigido (2026-05-31)**: `nu` usa `DOCKER_CALLBACK_HOST=host.docker.internal` (TCP reachable). `CALLBACK_HOST` só serve para bind local. |
| `_ensure_subscription()` falha se Android ainda não se registou | Iniciar Streamlit depois da app Android |
| S3 ACK ~73% packet loss (primeira versão) | **Corrigido (2026-06-02)**: subscriptions condicionais em `websocket_client.connect()` — só cria `sub-streamlit-tel` se `_telemetry_cb` registado; S3 não cria sub de telemetria e evita 240 notificações/min a congestionar a fila WS do CSE |
| S3 ACK ~56% packet loss após fix anterior | **Corrigido (2026-06-03)**: `_run_scenario_2` envia `setTelemetryRate(60000)` + `sleep(3)` antes do burst de comandos; telemetria do Android a 4 msg/s bloqueava o event loop asyncio do CSE via writes TinyDB síncronos |
| Runs S1/S2 seguintes bloqueiam após run a 16 msg/s (0 notificações) | **Corrigido (2026-06-04)**: `_run_scenario_1` envia `setTelemetryRate(60000)` + `sleep(3)` no `finally` antes de `disconnect()`. Sem isto, o Android continua a 16 msg/s entre runs; TinyDB writes bloqueiam o CSE asyncio loop ~97% do tempo e o run seguinte recebe 0 notificações. **Nota de operação:** após sessão de 16 msg/s, reiniciar o CSE (`docker compose restart`) antes de retomar testes. |
| S3 primeiros 9 comandos com timeout (cin_create_ms=6–7s) após S2 a 16 msg/s | **Resultado de paper, não bug** (2026-06-04): `_wait_for_cse_recovery()` foi removida intencionalmente. O benchmark mede a performance real do CSE incluindo o backlog TinyDB residual. O atraso nos primeiros comandos é um dado válido que documenta o tempo de recuperação do CSE. Após sessão a 16 msg/s, fazer `docker compose restart` antes de uma nova sessão de testes. |
| S3 latência_ms ≈ -1880ms (negativa) | **NTP offset, não bug**: `latency_ms = t_recv_ms − t_cmd_ms`; RC clock ~1880ms atrás do clock do Streamlit. Valor bruto mantido para correcção na análise. Documentar no paper como limitação NTP. |
| S2 (16 msg/s): latência crescente 180ms→84s, 69% packet loss | **Resultado de paper, não bug**: ACME CSE v2025.11 com TinyDB entrega notificações WS a ~4.9 msg/s. A 16 msg/s, a fila cresce 11.1 entradas/s → latência linear ao longo do run. Documentar como teto de throughput do CSE. |

---

## Dependências (requirements.txt)

```
streamlit==1.45.1
paho-mqtt==2.1.0
websockets==13.1
aiocoap==0.4.8
requests==2.32.3
python-dotenv==1.0.1
pandas==2.2.2
plotly==5.22.0
```

Versões pinadas para reprodutibilidade (requisito do artigo académico).

---

## Referências

- `docs/ai-context/frontend-dev.md` — contexto de desenvolvimento completo
- `docs/ai-context/cse-dev.md` — integração com ACME CSE v2025.11
- `docs/ai-context/project-context.md` — contexto completo do projecto
- `src/android/CLAUDE.md` — app Android (contraparte do benchmark)
