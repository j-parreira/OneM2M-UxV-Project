# Referência de Telemetria

> Todos os campos enviados pela app para o servidor via WebSocket a cada 250ms.

## Estrutura do JSON

```json
{
  "lat": 39.933219,
  "lng": -8.892509,
  "alt": 45.2,
  "velX": 1.2,
  "velY": -0.5,
  "velZ": 0.3,
  "isFlying": true,
  "satCount": 18,
  "rft": 1200,
  "isGoingHome": false,
  "areMotorsOn": true,
  "isHomeLocationSet": true,
  "homeLocation": { "lat": 39.933219, "lng": -8.892509 },
  "hdg": 180.5,
  "isTraveling": false,
  "model": "Mavic 2 Enterprise Advanced",
  "bat": {
    "lvl": 85,
    "remaining": 3200,
    "temperature": 38.5,
    "charging": false,
    "connectionState": "CONNECTED",
    "voltage": 16800,
    "current": 250
  },
  "gimbal": {
    "pitch": -45.0,
    "roll": 0.0,
    "yaw": 12.3
  },
  "rcBat": {
    "lvl": 92,
    "remainingMah": 3200,
    "charging": false
  },
  "zoom": 1.0,
  "cameraMode": "RGB"
}
```

## Campos detalhados

### Posição e navegação

| Campo | Tipo | Descrição | Notas |
|---|---|-|---|
| `lat` | `double` | Latitude atual do drone (graus) | WGS84 |
| `lng` | `double` | Longitude atual do drone (graus) | WGS84 |
| `alt` | `double` | Altitude relativa ao takeoff (m) | Relativo ao ponto de descolagem |
| `homeLocation` | `object` | Coordenada do ponto home | `{ lat, lng }` |

### Velocidade e movimento

| Campo | Tipo | Descrição | Notas |
|---|---|-|---|
| `velX` | `double` | Velocidade no eixo X (m/s) | Eixo do corpo |
| `velY` | `double` | Velocidade no eixo Y (m/s) | Eixo do corpo |
| `velZ` | `double` | Velocidade no eixo Z (m/s) | + = sobe, - = desce |

### Estado de voo

| Campo | Tipo | Descrição | Valores |
|---|---|-|---|
| `isFlying` | `boolean` | Drone em voo | `true`/`false` |
| `isTraveling` | `boolean` | Navegação GPS em curso | `true` se PID ativo |
| `satCount` | `int` | Satélites GPS visíveis | - |
| `rft` | `double` | Tempo restante de voo estimado (s) | Remaining Flight Time |
| `isGoingHome` | `boolean` | A regressar ao home | `true`/`false` |
| `areMotorsOn` | `boolean` | Motores ligados | `true`/`false` |
| `isHomeLocationSet` | `boolean` | Home location definido | `true`/`false` |
| `hdg` | `double` | Direção do drone (graus) | 0=N, 90=E, 180=S, 270=W |
| `model` | `string` | Modelo do drone | "Mavic 2 Enterprise Advanced" |

### Bateria do drone

| Campo | Tipo | Descrição | Notas |
|---|---|-|---|
| `bat.lvl` | `int` | Nível da bateria (%) | 0 a 100 |
| `bat.remaining` | `int` | Energia restante (mAh) | `BatteryState.getChargeRemaining()` |
| `bat.temperature` | `float` | Temperatura da bateria (°C) | Alerta > 60°C |
| `bat.charging` | `boolean` | A carregar | `true`/`false` |
| `bat.connectionState` | `string` | Estado da ligação | "CONNECTED", "DISCONNECTED", etc. |
| `bat.voltage` | `int` | Voltagem da bateria (mV) | `BatteryState.getVoltage()` |
| `bat.current` | `int` | Corrente da bateria (mA) | `BatteryState.getCurrent()` |

### Bateria do controlador

| Campo | Tipo | Descrição | Notas |
|---|---|-|---|
| `rcBat.lvl` | `int` | Nível da bateria do controlador (%) | 0 a 100 |
| `rcBat.remainingMah` | `int` | Energia restante (mAh) | - |
| `rcBat.charging` | `boolean` | Controlador a carregar | `true`/`false` |

### Gimbal

| Campo | Tipo | Descrição | Range M2EA |
|---|---|-|---|
| `gimbal.pitch` | `float` | Inclinação do gimbal (graus) | -90 a +30 |
| `gimbal.roll` | `float` | Rotação lateral do gimbal (graus) | 0 (estabilizado) |
| `gimbal.yaw` | `float` | Direção horizontal do gimbal (graus) | -75 a +75 |

### Câmera

| Campo | Tipo | Descrição | Valores |
|---|---|-|---|
| `cameraMode` | `string` | Modo atual da câmara | `"RGB"`, `"IR"`, `"SPLIT"` |
| `zoom` | `float` | Fator de zoom atual | 1.0 a 32.0 |

---

## Exemplos de payloads

### Em voo normal (RGB)

```json
{
  "lat": 39.933219,
  "lng": -8.892509,
  "alt": 45.2,
  "velX": 0.0,
  "velY": 0.0,
  "velZ": 0.0,
  "isFlying": true,
  "satCount": 18,
  "rft": 1200.0,
  "isGoingHome": false,
  "areMotorsOn": true,
  "isHomeLocationSet": true,
  "homeLocation": { "lat": 39.933219, "lng": -8.892509 },
  "hdg": 180.5,
  "isTraveling": false,
  "model": "Mavic 2 Enterprise Advanced",
  "bat": { "lvl": 85, "remaining": 3200, "temperature": 38.5, "charging": false, "connectionState": "CONNECTED", "voltage": 16800, "current": 250 },
  "gimbal": {
    "pitch": -45.0,
    "roll": 0.0,
    "yaw": 12.3
  },
  "rcBat": { "lvl": 92, "remainingMah": 3200, "charging": false },
  "zoom": 1.0,
  "cameraMode": "RGB"
}
```

### Em navegação GPS (waypoint)

```json
{
  "lat": 39.934012,
  "lng": -8.891234,
  "alt": 50.0,
  "velX": 2.5,
  "velY": 1.8,
  "velZ": 0.0,
  "isFlying": true,
  "satCount": 20,
  "rft": 1080.0,
  "isGoingHome": false,
  "areMotorsOn": true,
  "isHomeLocationSet": true,
  "homeLocation": { "lat": 39.933219, "lng": -8.892509 },
  "hdg": 90.0,
  "isTraveling": true,
  "model": "Mavic 2 Enterprise Advanced",
  "bat": { "lvl": 82, "remaining": 3000, "temperature": 39.0, "charging": false, "connectionState": "CONNECTED", "voltage": 16700, "current": 310 },
  "gimbal": { "pitch": -45.0, "roll": 0.0, "yaw": 180.5 },
  "rcBat": { "lvl": 90, "remainingMah": 3000, "charging": false },
  "zoom": 2.5,
  "cameraMode": "RGB"
}
```

### Com zoom aplicado (modo IR)

```json
{
  "lat": 39.933219,
  "lng": -8.892509,
  "alt": 45.2,
  "isFlying": true,
  "isTraveling": false,
  "bat": { "lvl": 80, "remaining": 2900, "temperature": 38.0, "charging": false, "connectionState": "CONNECTED", "voltage": 16600, "current": 180 },
  "gimbal": { "pitch": -30.0, "roll": 0.0, "yaw": 0.0 },
  "rcBat": { "lvl": 88, "remainingMah": 2800, "charging": false },
  "zoom": 8.0,
  "cameraMode": "IR"
}
```
