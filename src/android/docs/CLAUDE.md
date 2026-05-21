# docs/CLAUDE.md — Documentation Reference Index

> Índice e guia de navegação para a pasta `docs/`.

---

## Ficheiros

| Ficheiro | Descrição | Estado |
|------|---------|------|
| `DJIMobileSDKAndroidAPIReference.md` | Referência completa da DJI SDK v4 API | Ativo |
| `DboidsView.java` | Código legacy monolítico (~1200 linhas) — referência de implementação funcional | READ ONLY |
| `command-reference.md` | Referência de comandos WebSocket recebidos (17 comandos) | Ativo |
| `telemetry-reference.md` | Referência de telemetria enviada (20+ campos, estrutura JSON) | Ativo |
| `mavic2_gimbal.txt` | Exemplos de controlo do gimbal (pitch/yaw) | Referência |

---

## DJIMobileSDKAndroidAPIReference.md

### Secções principais

| Secção | Conteúdo |
|------|------|
| Manager Classes | `DJISDKManager`, `KeyManager`, `LiveStreamManager`, `MissionControl`, etc. |
| Product Classes | `Aircraft`, `HandHeld` |
| Component Classes | `FlightController`, `Camera`, `Gimbal`, `Battery`, `AirLink`, `RemoteController`, etc. |
| Mission Classes | `WaypointMissionOperator`, `WaypointV2MissionOperator`, `FollowMeMissionOperator`, etc. |
| Misc Classes | `CommonCallbacks`, `DJIError`, `DJIDiagnostics`, `DJICodecManager` |
| Apêndice A | Acesso típico ao SDK (exemplos de código) |
| Apêndice B | Virtual Sticks setup |
| Apêndice C | Waypoint Mission exemplo completo |
| Apêndice D | Hierarquia de classes (árvores) |

### Uso rápido

```java
// Flight controller
FlightController fc = aircraft.getFlightController();

// Camera
Camera cam = aircraft.getCameras().get(0);
cam.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, callback);

// Gimbal
Gimbal gimbal = aircraft.getGimbals().get(0);
Rotation r = new Rotation.Builder().pitch(-45f).mode(RotationMode.ABSOLUTE_ANGLE).build();
gimbal.rotate(r, callback);

// Virtual sticks
fc.setVirtualStickModeEnabled(true, callback);
fc.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
fc.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
fc.sendVirtualStickFlightControlData(new FlightControlData(0, 0.5f, 0, 0), callback);

// Waypoint
WaypointMissionOperator op = DJISDKManager.getInstance().getMissionControl().getWaypointMissionOperator();
op.startMission(callback);
```

---

## DboidsView.java — Referência Legacy

> ⚠️ **Atenção:** Ficheiro READ ONLY. Referências de linha podem ficar desatualizadas.
> Última verificação: 2026-05-19 — se o ficheiro mudou, valida as linhas antes de confiar nos números.

### Secções relevantes

| Método | Linha | Propósito |
|-------|------|------|
| `handleMessage()` | 281 | Parser de comandos WebSocket (switch) |
| `startMission()` | 399 | Execução de missão com PID loop |
| `gpsInputTo()` | 582 | Navegação por GPS com PID |
| `virtualSticksInput()` | 892 | Recebe inputs remotos de sticks |
| `sendStatus()` | 1059 | Telemetria para WebSocket |
| `startRTMP()` | 1029 | Streaming RTMP |
| `perform360()` | 1189 | Rotação 360 graus |
| `takeOffTo()` | 1260 | Descolagem com altitude target |
| `measure()` | 861 | Haversine distance (meters) |
| `getTargetYaw()` | 856 | Target heading calculation |

### Padrões identificados (reutilizáveis)

- **Zoom conversion**: `focalLength = factor × 240`
- **PID parameters**: `kp=0.2, ki=0.0001, kd=0.2`
- **Telemetry interval**: `250ms` (TimerTask)
- **Virtual stick update rate**: `200ms`
- **Distance thresholds**: `> 3m` start PID, `> 2m` stop PID
- **GoHome altitude**: `max(currentAltitude, 80)m`
- **Safety check**: `isFlying()` before every flight command

---

## command-reference.md — Comandos WebSocket

### Resumo dos 17 comandos

| Comando | Parâmetros | Descrição |
|---------|-----------|-----------|
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
| `startRTMP` | nenhum | Stream RTMP |
| `perform360` | nenhum | Rotação 360° |
| `identify` | `state` (bool) | LEDs on/off |

### Estrutura geral

```json
{ "command": "<nome>", "<parametro>": <valor> }
```

---

## telemetry-reference.md — Telemetria Enviada

### Campos principais (20+ campos a cada 250ms)

**Estrutura:**
- `bat` — bateria do drone: `{ lvl, remaining, temperature, charging, connectionState, voltage, current }`
- `rcBat` — bateria do RC: `{ lvl, remainingMah, charging }`
- `homeLocation` — `{ lat, lng }`
- Posição: `lat`, `lng`, `alt`
- Velocidade: `velX`, `velY`, `velZ`
- Estado: `isFlying`, `isTraveling`, `isGoingHome`, `areMotorsOn`, `isHomeLocationSet`
- Outros: `satCount`, `rft`, `hdg`, `model`, `gimbal` (`pitch`, `roll`, `yaw`), `zoom`, `cameraMode`

---

## mavic2_gimbal.txt — Gimbal Control

### Descrição

Exemplos de implementação para controlo do gimbal via `Rotation.Builder`.

### Parâmetros M2EA

| Eixo | Range | Unidade |
|-----|------|------|
| Pitch | -90 a +30 | graus |
| Yaw (pan) | -75 a +75 | graus |

### Exemplo de uso

```java
// Absolute angle (posição fixa)
Rotation abs = new Rotation.Builder()
    .pitch(-45f)
    .mode(RotationMode.ABSOLUTE_ANGLE)
    .time(1)
    .build();
gimbal.rotate(abs, callback);

// Relative angle (delta)
Rotation rel = new Rotation.Builder()
    .pitch(-5f)
    .mode(RotationMode.RELATIVE_ANGLE)
    .build();
gimbal.rotate(rel, callback);

// Speed-based (rampa)
Rotation speed = new Rotation.Builder()
    .pitch(-45f)
    .mode(RotationMode.SPEED)
    .time(2)
    .build();
gimbal.rotate(speed, callback);
```

### Problemas conhecidos

- Gimbal precisa estar conectado antes de rotate (check `gimbal.isConnected()`)
- Pitch > 0 degrada imagem no M2EA (máximo +30)
- Pan fora de -75/+75 resulta em erro SDK
