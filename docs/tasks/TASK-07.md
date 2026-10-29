# Task ID: TASK-07 — Pipeline de Captura de Tela e Áudio de Baixa Latência (PipeWire + GStreamer)

## 1. Contexto Arquitetural
Para o modo Desktop Remoto funcionar a 60 FPS com latência imperceptível tanto em Wayland quanto em X11, precisamos capturar o vídeo via **PipeWire** (negociado via `xdg-desktop-portal` ScreenCast no Wayland ou `ximagesrc` como fallback em X11) e o áudio do monitor do sistema, codificando tudo em H.264 *ultra-low-latency* e Opus via **GStreamer**.

## 2. Dependências
- `TASK-01`, `TASK-06` (`godbus/dbus`).

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/capture/portal_screencast.go`
- `linux-daemon/internal/capture/pipeline.go`
- `linux-daemon/internal/capture/capture_test.go`

## 4. Requisitos Técnicos
1. **Negociação XDG Desktop Portal (`portal_screencast.go`):**
   - Em sessões Wayland (`$XDG_SESSION_TYPE == "wayland"`), comunicar via D-Bus com `org.freedesktop.portal.Desktop` (`org.freedesktop.portal.ScreenCast`):
     - `CreateSession` -> `SelectSources` (Monitor, cursor embutido, `persist_mode = 2` para salvar token de restauração e não pedir permissão toda vez) -> `Start` -> obter o `pipewire_node_id` e o file descriptor via `OpenPipeWireRemote`.
2. **Pipeline de Codificação de Vídeo e Áudio (`pipeline.go`):**
   - Expor uma interface `MediaSource` que emite pacotes elementares (`Sample` com bytes + duração) em dois canais: `VideoSamples() <-chan MediaSample` (H.264) e `AudioSamples() <-chan MediaSample` (Opus).
   - Detectar automaticamente o encoder H.264 disponível no sistema nesta ordem de prioridade:
     1. Hardware AMD/Intel: `vaapih264enc rate-control=cbr tune=low-power keyframe-period=60`
     2. Hardware NVIDIA: `nvh264enc preset=low-latency-hq rc-mode=cbr zerolatency=true`
     3. Software Fallback (CPU): `x264enc tune=zerolatency speed-preset=ultrafast key-int-max=60`
   - Incluir modo gerador sintético (`videotestsrc pattern=ball` + `audiotestsrc`) selecionável via flag ou variável de ambiente (`DECK_CAPTURE_TEST_SOURCE=1`) para permitir testes automatizados de ponta a ponta em servidores headless/CI sem monitor conectado.

## 5. Restrições
- Zero B-frames em qualquer perfil de codificação H.264 (B-frames introduzem atraso de reordenação inaceitável para controle de mouse em tempo real).
- Encerrar o pipeline imediatamente quando o cliente parar o stream de Desktop Remoto para não consumir GPU/CPU à toa.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/capture/...`.
2. O teste deve iniciar o `MediaSource` em modo sintético (`DECK_CAPTURE_TEST_SOURCE=1`), verificar se recebe pelo menos 15 NAL units H.264 válidas em 1 segundo e encerrar o pipeline sem vazamento de goroutines ou processos.

---

