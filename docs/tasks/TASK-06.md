# Task ID: TASK-06 — Telemetria do Sistema (`/proc`, `/sys`) e Integração D-Bus (`MPRIS2` / `logind`)

## 1. Contexto Arquitetural
Para que o celular funcione como um painel de controle completo ao lado do monitor, os botões do Deck precisam exibir gráficos em tempo real (CPU, RAM, Temperatura, Rede) e controlar mídia (Spotify, navegador, VLC) e sessão do sistema (Bloquear/Destravar tela, Suspender, Desligar) via barramento nativo D-Bus.

## 2. Dependências
- `TASK-01`, `TASK-03`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/telemetry/collector.go`
- `linux-daemon/internal/telemetry/collector_test.go`
- `linux-daemon/internal/dbus/media_mpris.go`
- `linux-daemon/internal/dbus/power_logind.go`

## 4. Requisitos Técnicos
1. **Coletor de Telemetria (`collector.go`):**
   - Ler diretamente do sistema de arquivos virtual do Linux sem depender de binários externos:
     - `/proc/stat`: calcular o percentual real de uso de CPU entre dois ticks consecutivos ($1 - \frac{\Delta \text{idle}}{\Delta \text{total}}$).
     - `/proc/meminfo`: calcular RAM usada e total (`MemTotal - MemAvailable`).
     - `/sys/class/hwmon/hwmon*/temp*_input` ou `/sys/class/thermal/thermal_zone*/temp`: ler temperaturas de CPU/GPU em graus Celsius.
     - `/proc/net/dev`: calcular taxa de download/upload (`rx_kbps`, `tx_kbps`) nas interfaces não-loopback.
   - Emitir `TelemetrySnapshot` periodicamente (ex: a cada 1000ms) apenas quando houver pelo menos 1 cliente autenticado conectado.
2. **Controle de Mídia via D-Bus (`media_mpris.go`):**
   - Usar `github.com/godbus/dbus/v5` conectado ao `SessionBus`.
   - Listar destinos `org.mpris.MediaPlayer2.*` ativos, despachar comandos `PlayPause`, `Next`, `Previous`, `Stop` na interface `org.mpris.MediaPlayer2.Player` e ler `Metadata` (`xesam:title`, `xesam:artist`) + `PlaybackStatus`.
3. **Controle de Sessão e Energia via D-Bus (`power_logind.go`):**
   - Conectar ao `SystemBus` no objeto `org.freedesktop.login1` (`/org/freedesktop/login1`, interface `org.freedesktop.login1.Manager`).
   - Implementar ações para `LockSessions`, `UnlockSessions`, `Suspend`, `Reboot` e `PowerOff`.

## 5. Restrições
- Caso o ambiente de teste (ex: container Docker sem D-Bus ativo) não possua `SessionBus` ou `SystemBus`, os módulos D-Bus devem retornar erros tratados sem derrubar o daemon.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/telemetry/...`.
2. Validar que o coletor retorna `0 <= cpu_percent <= 100`, `ram_total_mb > 0` e taxas de rede válidas no host Linux atual.

---

