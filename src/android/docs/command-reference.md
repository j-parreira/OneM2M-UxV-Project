# Referência de Comandos

> Comandos que o Streamlit pode enviar para a app via OneM2M (CIN no container `commands`).
> Formato: `{"command": "<nome>", ...}` no campo `con` do `m2m:cin`.
> Para medição de latência (Cenário 2), incluir também `"t_cmd_ms"` e `"seq_cmd"`:
> `{"command": "takeoff", "t_cmd_ms": 1748000000000, "seq_cmd": 1}`

## Formato geral

Todos os comandos seguem a estrutura:

```json
{
  "command": "<nome_do_comando>",
  "<parametro1>": <valor1>,
  "<parametro2>": <valor2>
}
```

---

## Comandos de Voo

### `takeoff`

Descola o drone para a altitude de missão (ou 80m se não definida).

```json
{ "command": "takeoff" }
```

### `land`

Aterra o drone.

```json
{ "command": "land" }
```

### `motors`

Liga ou desliga os motores.

| Parametro | Tipo | Descrição | Valores |
|---|---|---|-|
| `state` | `boolean` | Estado dos motores | `true` = ligar, `false` = desligar |

```json
{ "command": "motors", "state": true }
```

### `startGoHome`

Inicia o retorno ao ponto home (Go Home).

```json
{ "command": "startGoHome" }
```

---

## Navegação

### `gpsInput`

Navega para coordenadas GPS via PID + virtual sticks.

| Parametro | Tipo | Descrição |
|---|---|-|
| `lat` | `double` | Latitude destino (graus) |
| `lng` | `double` | Longitude destino (graus) |

```json
{ "command": "gpsInput", "lat": 39.934012, "lng": -8.891234 }
```

### `gpsInput360Mapping`

Alias de `gpsInput` (compatible com mapeamento de 360).

```json
{ "command": "gpsInput360Mapping", "lat": 39.934012, "lng": -8.891234 }
```

### `virtualSticksInput`

Envia controle direto dos sticks virtuais (controle manual remoto).

| Parametro | Tipo | Descrição | Range |
|---|---|-|---|
| `roll` | `float` | Rotação lateral | -1.0 a 1.0 |
| `pitch` | `float` | Inclinação frente/trás | -1.0 a 1.0 |
| `yaw` | `float` | Rotação horizontal | -1.0 a 1.0 |
| `throttle` | `float` | Subida/descida | -1.0 a 1.0 |

```json
{ "command": "virtualSticksInput", "roll": 0.0, "pitch": 0.5, "yaw": 0, "throttle": 0.2 }
```

### `virtualSticks`

Liga ou desliga o modo virtual stick.

| Parametro | Tipo | Descrição | Valores |
|---|---|-|---|
| `state` | `boolean` | Estado do modo virtual stick | `true` = ativar, `false` = desativar |

```json
{ "command": "virtualSticks", "state": true }
```

---

## Gimbal

### `gimbalAngle`

Define o ângulo do gimbal (pitch + yaw).

> **M2EA**: pitch -90° (baixo) a +30° (cima), yaw -75° a +75° (pan).
> O valor de pitch é clampado internamente a estes limites.

| Parametro | Tipo | Descrição | Range | Obrigatório |
|---|---|-|-|
| `pitch` | `float` | Inclinação (tilt) | -90 a +30 | Sim |
| `yaw` | `float` | Direção (pan) | -75 a +75 | Sim |
| `mode` | `string` | Modo de rotação | `"absolute"` (default), `"relative"` | Não |

**Modos:**

| Modo | Comportamento | Exemplo |
|---|---|-|
| `absolute` | Vai direto ao valor | pitch=-45 vai para -45° |
| `relative` | Soma ao valor atual | pitch=-5 desce 5° do atual |

```json
{ "command": "gimbalAngle", "pitch": -45, "yaw": 0, "mode": "absolute" }
{ "command": "gimbalAngle", "pitch": -5, "yaw": 10, "mode": "relative" }
{ "command": "gimbalAngle", "pitch": 0 }
```

### `gimbalReset`

Reposição do gimbal à posição neutra (0° pitch, 0° yaw).

```json
{ "command": "gimbalReset" }
```

---

## Missões

### `startMission`

Inicia uma missão de waypoints.

| Parametro | Tipo | Descrição | Default |
|---|---|-|-|
| `startAction` | `string` | Ação de início | `null` |
| `endAction` | `string` | Ação de fim | `null` |
| `altitude` | `float` | Altitude da missão (m) | 0 |
| `repeat` | `int` | Número de repetições | 0 |
| `path` | `array` | Waypoints `[{"lat": x, "lng": y}, ...]` | `null` |

**Valores de `startAction` e `endAction`:** `"takeoff"`, `"land"`, `"goHome"`, `null`

```json
{
  "command": "startMission",
  "startAction": "takeoff",
  "endAction": "land",
  "altitude": 50,
  "repeat": 1,
  "path": [
    {"lat": 39.934, "lng": -8.891},
    {"lat": 39.935, "lng": -8.892}
  ]
}
```

### `stopMission`

Para a missão imediatamente.

```json
{ "command": "stopMission" }
```

### `pauseMission`

Pausa ou retoma a missão (toggle).

```json
{ "command": "pauseMission" }
```

---

## Câmera

### `setZoom`

Define o fator de zoom (1.0x a 32.0x).

| Parametro | Tipo | Descrição | Range |
|---|---|-|---|
| `factor` | `float` | Fator de zoom | 1.0 a 32.0 |

O valor é convertido internamente para `focalLength = factor * 240` (mm).

```json
{ "command": "setZoom", "factor": 2.5 }
```

### `setCameraMode`

Altera o modo da câmara e o stream de vídeo.

| Parametro | Tipo | Descrição | Valores |
|---|---|-|---|
| `mode` | `string` | Modo da câmara | `"RGB"`, `"IR"`, `"SPLIT"` |

**Mapeamento de stream:**

| Mode | Stream | Descrição |
|---|---|-|
| `RGB` | WIDE | Câmera RGB primária |
| `IR` | INFRARED_THERMAL | Câmera térmica FLIR |
| `SPLIT` | PIP | Imagem sobreposta (RGB + térmica) |

```json
{ "command": "setCameraMode", "mode": "IR" }
```

---

## Streaming

### `startRTMP`

Inicia o stream de vídeo via RTMP.

URL gerada: `rtmp://<server>:1935/<droneSerialNumber>`

```json
{ "command": "startRTMP" }
```

---

## Especial

### `setTelemetryRate`

Altera o intervalo de envio de telemetria (Cenário 1 — testar diferentes taxas).

| Parâmetro | Tipo | Descrição | Valores típicos |
|---|---|-|---|
| `intervalMs` | `int` | Intervalo em ms | 100 (10/s), 200 (5/s), 1000 (1/s) |

```json
{ "command": "setTelemetryRate", "intervalMs": 200 }
```

> O mínimo seguro é 50 ms (20 msg/s). Abaixo disso pode saturar o canal.
> A alteração é efectiva imediatamente — o timer é reiniciado com o novo intervalo.
> Para repor o valor por defeito: `{"command": "setTelemetryRate", "intervalMs": 250}`

---

### `perform360`

Executa uma rotação de 360° em yaw para identificação visual (a 30°/s).

```json
{ "command": "perform360" }
```

### `identify`

Liga ou desliga LEDs e faróis do drone.

| Parametro | Tipo | Descrição | Valores |
|---|---|-|---|
| `state` | `boolean` | Estado dos LEDs | `true` = ligar, `false` = desligar |

```json
{ "command": "identify", "state": true }
```

---

## Tabela resumo

| Comando | Parâmetros | Descrição |
|---|---|-|
| `takeoff` | nenhum | Descolagem |
| `land` | nenhum | Aterragem |
| `motors` | `state` (bool) | Motores on/off |
| `startGoHome` | nenhum | Go Home |
| `gpsInput` / `gpsInput360Mapping` | `lat`, `lng` | Navegação GPS |
| `virtualSticksInput` | `roll`, `pitch`, `yaw`, `throttle` | Controlo manual |
| `virtualSticks` | `state` (bool) | Virtual sticks on/off |
| `gimbalAngle` | `pitch`, `yaw`, `mode` | Direcionar gimbal |
| `gimbalReset` | nenhum | Reposicionar gimbal |
| `startMission` | `startAction`, `endAction`, `altitude`, `repeat`, `path` | Missão waypoints |
| `stopMission` | nenhum | Parar missão |
| `pauseMission` | nenhum | Pausa/retoma missão |
| `setZoom` | `factor` | Zoom 1.0x-32.0x |
| `setCameraMode` | `mode` | RGB/IR/SPLIT |
| `startRTMP` | nenhum | Stream RTMP (produção, sem efeito no benchmark) |
| `perform360` | nenhum | Rotação 360° |
| `identify` | `state` (bool) | LEDs on/off |
| `setTelemetryRate` | `intervalMs` | **Benchmark** — altera taxa de telemetria |

---

## Campos de medição para Cenário 2

Para medir latência de comandos, o Streamlit deve incluir em cada CIN:

| Campo | Tipo | Descrição |
|---|---|---|
| `t_cmd_ms` | `long` | Timestamp de envio pelo Streamlit (epoch ms) |
| `seq_cmd` | `int` | Número de sequência do comando (detectar perda) |

A app responde com um CIN em `/id-in/uxv/ack`:

| Campo | Tipo | Descrição |
|---|---|---|
| `command` | `string` | Nome do comando executado |
| `seq_cmd` | `int` | Número de sequência (do Streamlit) |
| `t_cmd_ms` | `long` | Timestamp original do Streamlit |
| `t_recv_ms` | `long` | Timestamp de recepção na app |
| `t_exec_ms` | `long` | Timestamp após dispatch do comando |

**Latência medida pelo Streamlit:** `t_recv_ms - t_cmd_ms`
