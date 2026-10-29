# Catálogo de Especificações para Agentes (`TASK_SPEC`)
**Projeto:** Linux Deck & Remote Control (Host Linux + Cliente Android)  
**Arquitetura:** Contract-First (Protobuf v3) | Daemon Linux em **Go (Golang)** | App Android em **Kotlin (Jetpack Compose)**

> **Dica de Automação:** Cada seção abaixo é delimitada pelo marcador `<!-- SPLIT:TASK-XX.md -->`. Para extrair automaticamente este documento em 13 arquivos individuais dentro de `docs/tasks/`, execute na raiz do projeto:
> ```bash
> mkdir -p docs/tasks && awk '/<!-- SPLIT:TASK-[0-9]+\.md -->/{match($0, /TASK-[0-9]+\.md/, a); file="docs/tasks/"a[0]; next} file{print > file}' docs/tasks/TASK_SPECS.md
> ```

---

<!-- SPLIT:TASK-01.md -->
# Task ID: TASK-01 — Contrato do Protocolo (Protobuf) e Geração de Código

## 1. Contexto Arquitetural
Estamos construindo um sistema de controle remoto total e Macro Deck entre Android (Kotlin) e Linux (Daemon em Go). Para evitar divergências de implementação entre os agentes que atuarão no servidor e no aplicativo móvel, toda a comunicação do canal de controle utiliza **Protocol Buffers (proto3)** encapsulados em um `Envelope` único trafegado via WebSocket/TLS.

## 2. Dependências
- Nenhuma (Task fundacional).

## 3. Escopo Exclusivo e Arquivos-Alvo
- `proto/deck/v1/deck.proto`
- `proto/deck/v1/profile_schema.json`
- `scripts/gen-proto.sh`
- `linux-daemon/go.mod` (inicialização do módulo `github.com/local/linux-deck-daemon`)
- `linux-daemon/pkg/protocol/v1/` (arquivos `.pb.go` gerados)

## 4. Requisitos Técnicos
1. Definir o arquivo `proto/deck/v1/deck.proto` com `syntax = "proto3";` contendo:
   - **`Envelope`**: `string request_id`, `int64 timestamp_ms`, e um bloco `oneof payload` englobando todas as mensagens do sistema.
   - **Autenticação & Controle (`HandshakeRequest`, `HandshakeResponse`, `Ping`, `Pong`, `ErrorResponse`)**: versões de protocolo, nome do dispositivo e status de sessão.
   - **Input Virtual (`MouseMoveEvent`, `MouseButtonEvent`, `MouseScrollEvent`, `KeyEvent`, `KeyComboEvent`)**:
     - Suporte a modo relativo (`dx`, `dy` em pixels) e modo absoluto (`norm_x`, `norm_y` em `float` de `0.0` a `1.0` para o modo Desktop Remoto).
     - `MouseButton`: `LEFT`, `RIGHT`, `MIDDLE`, `BACK`, `FORWARD` e ações `PRESS`, `RELEASE`, `CLICK`.
   - **Macro Deck (`MacroExecRequest`, `MacroOutputEvent`, `MacroStateSync`, `DeckProfileSync`)**:
     - `button_id`, comando/ação, variáveis de ambiente, stream de `stdout`/`stderr`, `exit_code` e estado visual dinâmico do botão (título, cor, ícone ativo/inativo).
   - **Terminal Interativo PTY (`PtyOpenRequest`, `PtyDataEvent`, `PtyResizeEvent`, `PtyCloseEvent`)**:
     - `session_id`, `cols`, `rows`, e `bytes data` para I/O bruto do terminal.
   - **Telemetria e Sistema (`TelemetrySnapshot`, `MediaControlCommand`, `MediaStatusEvent`, `PowerControlCommand`)**:
     - Uso de CPU (%), RAM usada/total (MB), temperaturas (`map<string, float>`), velocidade de rede (`rx_kbps`, `tx_kbps`), metadados MPRIS2 (música, artista, `is_playing`) e ações de energia (`LOCK`, `UNLOCK`, `SUSPEND`, `REBOOT`, `POWEROFF`).
   - **Sinalização WebRTC (`WebRtcSignal`)**:
     - `enum SignalType { START_STREAM, STOP_STREAM, SDP_OFFER, SDP_ANSWER, ICE_CANDIDATE }`, `string payload_json`, configurações de resolução/FPS/bitrate.
2. Criar o arquivo `proto/deck/v1/profile_schema.json` especificando a estrutura de perfis do Deck (grids de linhas x colunas, páginas, tipos de botão: `COMMAND`, `KEY_COMBO`, `MEDIA`, `TELEMETRY_WIDGET`, `FOLDER`).
3. Criar `scripts/gen-proto.sh` executável (`chmod +x`) que invoca `protoc` para gerar o código Go dentro de `linux-daemon/pkg/protocol/v1` e valida a sintaxe do schema.

## 5. Restrições
- Não implemente lógica de rede ou de negócios nesta task.
- Todos os campos de telemetria e coordenadas devem usar tipos escalares eficientes (`float`, `int32`, `uint64`, `bytes`).

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `./scripts/gen-proto.sh` sem erros.
2. Executar `cd linux-daemon && go build ./pkg/protocol/v1/...` garantindo que o pacote Go gerado compila perfeitamente.

---

<!-- SPLIT:TASK-02.md -->
# Task ID: TASK-02 — Autenticação mTLS 1.3, Certificados X.509 e Pareamento via QR Code

## 1. Contexto Arquitetural
Como o daemon no Linux fornece acesso a um terminal interativo (`PTY`), execução de scripts e controle de mouse/teclado em nível de kernel, o canal de rede exige **Mutual TLS (mTLS 1.3)**. O pareamento inicial ocorre exibindo um QR Code no terminal do Linux (contendo IP/Hostname, Porta, Fingerprint SHA-256 do certificado do servidor e um Token de Pareamento de uso único válido por 5 minutos).

## 2. Dependências
- `TASK-01` (`pkg/protocol/v1`).

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/auth/ca.go`
- `linux-daemon/internal/auth/pairing.go`
- `linux-daemon/internal/auth/store.go`
- `linux-daemon/internal/auth/auth_test.go`

## 4. Requisitos Técnicos
1. **Gerenciamento de Certificados (`ca.go`):**
   - Na primeira execução, gerar um par de chaves ECDSA P-256 (ou Ed25519) e um certificado X.509 autoassinado de longa duração para o Daemon, salvando em `~/.config/deck-daemon/certs/server.{crt,key}` com permissão `0600`.
   - Configurar `tls.Config` exigindo `MinVersion: tls.VersionTLS13`.
2. **Armazenamento de Clientes Confiáveis (`store.go`):**
   - Persistir em `~/.config/deck-daemon/trusted_clients.json` a lista de dispositivos Android autorizados (`device_id`, `device_name`, `cert_fingerprint_sha256`, `paired_at`).
   - Implementar o callback `VerifyPeerCertificate` no `tls.Config` do servidor para validar se o certificado apresentado pelo cliente Android consta na lista de confiáveis.
3. **Fluxo de Pareamento e QR Code (`pairing.go`):**
   - Gerar um payload JSON de pareamento: `{"host": "...", "port": 8443, "pairing_port": 8444, "server_fp": "<sha256>", "token": "<random-256bit-hex>", "expires_at": 123456789}`.
   - Renderizar esse JSON como um QR Code em caracteres ASCII/UTF-8 diretamente no stdout do terminal (usando `github.com/mdp/qrterminal/v3` ou implementação equivalente).
   - Expor um endpoint temporário de pareamento que recebe o CSR/Certificado público do cliente junto com o `token` válido, registra o fingerprint no `store.go` e invalida o token imediatamente.

## 5. Restrições
- Não armazene chaves privadas sem permissão restrita (`0600`).
- Tokens de pareamento devem expirar em 5 minutos e suportar apenas 1 tentativa bem-sucedida.
- Não modifique outros pacotes além de `internal/auth`.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/auth/...`.
2. Os testes unitários devem cobrir: geração de certificados do servidor e do cliente em diretório temporário (`t.TempDir()`), validação de pareamento com token correto, rejeição de token expirado/inválido e rejeição de conexão TLS de um cliente com certificado não registrado no `store`.

---

<!-- SPLIT:TASK-03.md -->
# Task ID: TASK-03 — Descoberta mDNS (Zeroconf) e Servidor WebSocket/Protobuf Seguro

## 1. Contexto Arquitetural
O cliente Android precisa encontrar o PC Linux automaticamente na LAN via mDNS (`_linuxdeck._tcp.local.`) ou conectar diretamente via cabo USB (`adb reverse tcp:8443 tcp:8443`). Esta task cria o servidor principal de transporte sobre WebSocket + mTLS 1.3 que lê e escreve mensagens `Envelope` em binário Protobuf, além do `cmd/mock-client` para permitir que os agentes testem todas as features via linha de comando.

## 2. Dependências
- `TASK-01` (`pkg/protocol/v1`)
- `TASK-02` (`internal/auth`)

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/discovery/mdns.go`
- `linux-daemon/internal/server/ws_server.go`
- `linux-daemon/internal/server/router.go`
- `linux-daemon/cmd/daemon/main.go`
- `linux-daemon/cmd/mock-client/main.go`

## 4. Requisitos Técnicos
1. **Anúncio mDNS (`mdns.go`):**
   - Anunciar o serviço `_linuxdeck._tcp` na porta configurada (padrão `8443`) com registros TXT contendo `version=1`, `hostname=<os.Hostname>` e `fp=<fingerprint_curto>`.
2. **Servidor WebSocket sobre mTLS (`ws_server.go`):**
   - Usar `github.com/gorilla/websocket` ou `nhooyr.io/websocket` rodando sobre o `tls.Listener` autenticado do pacote `internal/auth`.
   - Suportar escrita concorrente thread-safe por cliente conectado (usando um canal `sendCh chan *pb.Envelope` por sessão) e *heartbeat* `Ping`/`Pong` a cada 10 segundos.
3. **Roteador de Mensagens (`router.go`):**
   - Definir uma interface modular `HandlerFunc func(ctx context.Context, client ClientSession, env *pb.Envelope) error` permitindo que as próximas tasks (Input, PTY, Macro, Telemetria, WebRTC) registrem seus handlers sem acoplamento.
4. **CLI do Daemon e Mock Client (`cmd/daemon` e `cmd/mock-client`):**
   - `daemon --pair` gera e exibe o QR Code / Token no terminal.
   - `mock-client pair --token <TOKEN> --host 127.0.0.1:8444` realiza o pareamento e salva o certificado de cliente em `~/.config/deck-daemon/mock-client/`.
   - `mock-client ping --host 127.0.0.1:8443` conecta via mTLS + WebSocket, envia um `Envelope{Ping}` e imprime a latência do `Envelope{Pong}` recebido.

## 5. Restrições
- Todas as mensagens WebSocket do canal de controle devem ser do tipo `BinaryMessage` serializadas via `proto.Marshal` / `proto.Unmarshal`.
- Garanta encerramento gracioso (`graceful shutdown`) ao receber `SIGINT`/`SIGTERM`.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Compilar ambos os binários: `cd linux-daemon && go build -o bin/daemon ./cmd/daemon && go build -o bin/mock-client ./cmd/mock-client`.
2. Executar teste de integração automatizado (`go test -v ./internal/server/...`) que sobe o servidor em porta aleatória, pareia um cliente em memória, envia um `Ping` Protobuf e valida o recebimento do `Pong`.

---

<!-- SPLIT:TASK-04.md -->
# Task ID: TASK-04 — Subsistema `/dev/uinput` (Mouse Relativo/Absoluto e Teclado Virtual)

## 1. Contexto Arquitetural
No Linux moderno com Wayland (Hyprland, KDE Plasma, GNOME, Sway), ferramentas antigas como `xdotool` não funcionam fora de janelas XWayland. Para ter controle total (incluindo tela de login e TTY), o daemon criará dispositivos virtuais em nível de kernel através de `/dev/uinput`.

## 2. Dependências
- `TASK-01`, `TASK-03`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/input/uinput_linux.go`
- `linux-daemon/internal/input/keymap.go`
- `linux-daemon/internal/input/handler.go`
- `linux-daemon/internal/input/input_test.go`
- `scripts/udev/99-deck-uinput.rules`
- `scripts/setup-permissions.sh`
- `linux-daemon/cmd/mock-client/main.go` (adicionar subcomando `input`)

## 4. Requisitos Técnicos
1. **Dispositivos Virtuais (`uinput_linux.go`):**
   - Criar uma interface `InputInjector` (com implementação real via `/dev/uinput` ou `github.com/bendahl/uinput` e uma implementação `Noop/Mock` para testes sem permissão de dispositivo).
   - Inicializar 3 dispositivos virtuais ao subir o daemon:
     1. `LinuxDeck Virtual Mouse (Relative)`: eventos `EV_REL` (`REL_X`, `REL_Y`, `REL_WHEEL`, `REL_HWHEEL`) e botões (`BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`, `BTN_SIDE`, `BTN_EXTRA`).
     2. `LinuxDeck Virtual TouchScreen (Absolute)`: eventos `EV_ABS` (`ABS_X` e `ABS_Y` com range `0..65535`) para mapear toques na tela remota (`0.0..1.0` -> `0..65535`) com precisão instantânea.
     3. `LinuxDeck Virtual Keyboard`: todos os keycodes Linux padrão (`KEY_A`..`KEY_Z`, `KEY_F1`..`KEY_F12`, `KEY_LEFTMETA`, `KEY_LEFTCTRL`, `KEY_LEFTALT`, `KEY_LEFTSHIFT`, multimídia, etc.).
2. **Mapeador de Atalhos e Texto (`keymap.go`):**
   - Converter strings legíveis (ex: `"SUPER+SHIFT+Q"`, `"CTRL+ALT+T"`, `"VOLUMEDOWN"`) em sequências de eventos `KeyDown` dos modificadores -> `KeyPress` da tecla principal -> `KeyUp` dos modificadores na ordem inversa.
3. **Permissões `udev`:**
   - Criar `scripts/udev/99-deck-uinput.rules` com:
     `KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"`
   - Criar `scripts/setup-permissions.sh` que explica/instala a regra `udev` e adiciona o usuário atual ao grupo `input`.
4. **Integração no Roteador e CLI:**
   - Registrar os handlers de `MouseMoveEvent`, `MouseButtonEvent`, `MouseScrollEvent`, `KeyEvent` e `KeyComboEvent` no `router.go`.
   - Adicionar ao `mock-client`: `mock-client input move --dx 100 --dy 50`, `mock-client input abs --x 0.5 --y 0.5` e `mock-client input combo "ctrl+alt+t"`.

## 5. Restrições
- Se `/dev/uinput` não estiver acessível no ambiente de execução por falta de permissão, o daemon deve logar um aviso claro instruindo a rodar `scripts/setup-permissions.sh` e iniciar em modo degradado (sem dar crash no processo inteiro).
- Feche os descritores de arquivo `uinput` no `Close()` do daemon para destruir os dispositivos virtuais.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/input/...` validando o parser de combos (`"CTRL+SHIFT+ESC"`, `"SUPER+1"`, etc.) e a conversão de coordenadas normalizadas (`0.0..1.0` -> `0..65535`).
2. Compilar `daemon` e `mock-client` sem erros.

---

<!-- SPLIT:TASK-05.md -->
# Task ID: TASK-05 — Engine de Macros (Deck) e Terminal Interativo PTY

## 1. Contexto Arquitetural
O modo Deck permite acionar scripts/comandos do Linux a partir de botões no celular (com feedback visual em tempo real se o comando está rodando, código de retorno e texto dinâmico no botão). Além disso, o usuário precisa de um Terminal Completo (`PTY`) para administrar o PC remotamente.

## 2. Dependências
- `TASK-01`, `TASK-03`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/macro/engine.go`
- `linux-daemon/internal/macro/profile_store.go`
- `linux-daemon/internal/pty/manager.go`
- `linux-daemon/internal/macro/macro_test.go`
- `linux-daemon/internal/pty/pty_test.go`
- `linux-daemon/cmd/mock-client/main.go` (adicionar subcomandos `macro` e `pty`)

## 4. Requisitos Técnicos
1. **Gerenciador de Perfis e Engine de Macros (`internal/macro`):**
   - Carregar e salvar perfis do Deck em `~/.config/deck-daemon/profiles/default.json` seguindo o schema da `TASK-01` (criar um perfil padrão rico caso não exista, contendo atalhos de terminal, controle de mídia, bloqueio de tela e widgets de CPU/RAM).
   - Ao receber `MacroExecRequest`, executar o comando configurado via `/bin/sh -c` com `context.WithTimeout` configurável, capturando `stdout` e `stderr` em streaming via `MacroOutputEvent` e enviando `MacroStateSync` ao finalizar.
   - Suportar comandos de "toggle/status" (polling opcional ou retorno de script que altera o rótulo/cor do botão no Android).
2. **Gerenciador de Sessões PTY (`internal/pty`):**
   - Utilizar `github.com/creack/pty` para instanciar o shell padrão do usuário (`$SHELL` ou `/bin/bash`) ao receber `PtyOpenRequest`.
   - Definir `TERM=xterm-256color` no ambiente do processo filho.
   - Redimensionar a janela do terminal via `pty.Setsize` ao receber `PtyResizeEvent` (`rows`, `cols`).
   - Encaminhar bytes lidos do descritor PTY como `PtyDataEvent` para o cliente WebSocket e escrever bytes recebidos do cliente no PTY.
   - Encerrar processos filhos e liberar file descriptors quando o cliente desconectar ou enviar `PtyCloseEvent`.

## 5. Restrições
- Nunca bloqueie a goroutine do roteador WebSocket aguardando o término de uma macro longa; cada execução roda em sua própria goroutine gerenciada por um `WaitGroup`.
- Limite o número máximo de sessões PTY simultâneas por cliente (ex: máx. 4 abas).

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/macro/... ./internal/pty/...`.
2. O teste de `pty` deve abrir uma sessão real `/bin/sh`, enviar `echo "HELLO_DECK"\n`, ler o output do canal de eventos e verificar que contém `"HELLO_DECK"`, redimensionar para `120x40` e fechar a sessão sem deixar processos órfãos.

---

<!-- SPLIT:TASK-06.md -->
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

<!-- SPLIT:TASK-07.md -->
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

<!-- SPLIT:TASK-08.md -->
# Task ID: TASK-08 — Integração WebRTC no Daemon (`pion/webrtc`) e Sinalização

## 1. Contexto Arquitetural
Com o canal de captura H.264/Opus pronto (`TASK-07`), precisamos transmiti-lo em tempo real via UDP/SRTP usando **WebRTC** (`github.com/pion/webrtc/v4`). A sinalização (troca de SDP Offer/Answer e candidatos ICE) ocorrerá dentro do próprio WebSocket mTLS já autenticado.

## 2. Dependências
- `TASK-01`, `TASK-03`, `TASK-07`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/webrtc/streamer.go`
- `linux-daemon/internal/webrtc/signaling_handler.go`
- `linux-daemon/internal/webrtc/webrtc_test.go`
- `linux-daemon/cmd/mock-client/main.go` (adicionar subcomando `stream-test`)

## 4. Requisitos Técnicos
1. **Gerenciador de PeerConnection (`streamer.go`):**
   - Ao receber `WebRtcSignal{type: START_STREAM}` do cliente Android:
     - Criar uma `webrtc.PeerConnection` com `TrackLocalStaticSample` para vídeo (`webrtc.MimeTypeH264`) e áudio (`webrtc.MimeTypeOpus`).
     - Iniciar o `MediaSource` (`TASK-07`) e bombear os samples de vídeo e áudio para as respectivas tracks.
     - Gerar um `SDP_OFFER` e enviá-lo ao cliente pelo WebSocket dentro de um `Envelope{WebRtcSignal}`.
2. **Negociação SDP e ICE (`signaling_handler.go`):**
   - Processar `SDP_ANSWER` recebido do cliente chamando `SetRemoteDescription`.
   - Trocar candidatos ICE (`OnICECandidate` <-> `AddICECandidate`) para estabelecer conexão direta na LAN, USB Tethering ou Tailscale.
   - Monitorar `OnConnectionStateChange`: caso mude para `Disconnected`, `Failed` ou `Closed`, desligar imediatamente o pipeline de captura da `TASK-07`.

## 5. Restrições
- Na rede local/USB/Tailscale, configure o ICE para funcionar mesmo sem internet externa (host candidates locais habilitados sem exigir STUN externo obrigatório).

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/webrtc/...`.
2. O teste automatizado deve criar uma `PeerConnection` do Daemon e uma `PeerConnection` de cliente em memória, realizar a troca de SDP Offer/Answer e ICE, transmitir 10 frames H.264 do modo de teste e confirmar a recepção dos pacotes RTP na ponta cliente.

---

<!-- SPLIT:TASK-09.md -->
# Task ID: TASK-09 — App Android: Setup do Projeto, Descoberta mDNS, Scanner QR e Cliente mTLS

## 1. Contexto Arquitetural
Iniciamos a construção do cliente Android nativo em **Kotlin + Jetpack Compose** dentro de `android-client/`. Esta task constrói a fundação do aplicativo: geração das classes Protobuf no Gradle, descoberta automática do PC via `NsdManager`, leitura do QR Code de pareamento e serviço persistente de conexão mTLS 1.3 + WebSocket.

## 2. Dependências
- `TASK-01`, `TASK-02`, `TASK-03`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`
- `android-client/app/src/main/AndroidManifest.xml`
- `android-client/app/src/main/java/com/linuxdeck/client/network/MdnsDiscoveryManager.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/security/MtlsKeyManager.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/network/DeckConnectionService.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/pairing/PairingScreen.kt`

## 4. Requisitos Técnicos
1. **Configuração Gradle + Protobuf:**
   - Configurar o plugin `com.google.protobuf` no `app/build.gradle.kts` apontando o `sourceSets` diretamente para `../../proto/deck/v1` (garantindo fonte única de verdade com o daemon Go).
   - Incluir dependências: Jetpack Compose (Material 3), OkHttp (WebSocket + TLS), BouncyCastle/KeyStore (geração de certificado cliente X.509), CameraX + MLKit Barcode Scanning (ou ZXing) e Coroutines/Flow.
2. **Segurança mTLS (`MtlsKeyManager.kt`):**
   - Gerar par de chaves ECDSA do cliente Android e certificado X.509 armazenado de forma segura.
   - Configurar `OkHttpClient` com `SSLSocketFactory` customizado que apresenta o certificado do cliente e valida o fingerprint SHA-256 do certificado do servidor Linux obtido no QR Code (*Certificate Pinning*).
3. **Descoberta e Conexão (`MdnsDiscoveryManager.kt` & `DeckConnectionService.kt`):**
   - Descobrir serviços `_linuxdeck._tcp.` na rede via `NsdManager` e também permitir conexão manual/USB (`127.0.0.1:8443` para `adb reverse`).
   - Manter um `ForegroundService` (`DeckConnectionService`) com reconexão automática exponencial, expondo `StateFlow<ConnectionState>` e `SharedFlow<Envelope>` para todas as telas do app.
4. **Tela de Pareamento (`PairingScreen.kt`):**
   - Listar PCs descobertos na rede, abrir scanner de QR Code (ou permitir colar o JSON/Token de pareamento) e executar o handshake inicial.

## 5. Restrições
- Não bloqueie a Main Thread do Android com operações de rede ou criptografia; utilize `Dispatchers.IO`.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. O projeto deve estar estruturado corretamente com todos os imports e tipos Protobuf correspondentes ao `deck.proto`.
2. Se o SDK Android e Gradle estiverem disponíveis no ambiente, `./gradlew assembleDebug` e `./gradlew testDebugUnitTest` devem executar sem erros.

---

<!-- SPLIT:TASK-10.md -->
# Task ID: TASK-10 — App Android: UI do Macro Deck Dinâmico e Widgets de Telemetria ao Vivo

## 1. Contexto Arquitetural
Esta é a tela principal de uso diário como "Stream Deck" ao lado do PC. Ela renderiza um grid dinâmico de botões altamente responsivos, sincronizados com o perfil JSON do Linux (`TASK-05`), além de widgets visuais de CPU, RAM, Temperatura e Controle de Mídia (`TASK-06`).

## 2. Dependências
- `TASK-05`, `TASK-06`, `TASK-09`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/DeckScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/DeckViewModel.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/components/DeckButtonCard.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/components/TelemetryWidgetCard.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/components/MediaControllerBar.kt`

## 4. Requisitos Técnicos
1. **Grid Configurável (`DeckScreen.kt`):**
   - Renderizar o grid de acordo com `rows` x `cols` do perfil ativo (suportando modo retrato e modo paisagem com `KeepScreenOn` ativado).
   - Suportar múltiplas páginas (deslizar horizontalmente com `HorizontalPager`) e pastas de botões.
2. **Botões com Feedback Tátil e Estado em Tempo Real (`DeckButtonCard.kt`):**
   - Ao tocar em um botão, acionar `HapticFeedbackType.LongPress` (vibração curta e firme), enviar `MacroExecRequest` ou `KeyComboEvent` e mostrar indicador visual de execução (`RUNNING`, `SUCCESS` verde ou `ERROR` vermelho caso `exit_code != 0`).
   - Permitir edição rápida do botão mantendo-o pressionado (alterar título, ícone, cor e comando/atalho e sincronizar o perfil atualizado de volta para o daemon Linux).
3. **Widgets de Telemetria e Mídia (`TelemetryWidgetCard.kt` & `MediaControllerBar.kt`):**
   - Consumir o fluxo `TelemetrySnapshot` no `DeckViewModel` e exibir medidores circulares/barras animadas de CPU (%), RAM (GB), Temperatura (°C) e tráfego de rede.
   - Exibir barra compacta de mídia MPRIS2 com título da faixa atual, artista e botões `Prev`, `Play/Pause`, `Next` e `Volume`.

## 5. Restrições
- Garanta recomposição isolada no Jetpack Compose: a chegada de um pacote `TelemetrySnapshot` a cada 1 segundo deve recompor **apenas** os cards de telemetria, sem redesenhar os botões estáticos do grid.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Verificar compilação limpa do módulo de UI e testes unitários do `DeckViewModel` validando a atualização de estado de botões após receber `MacroStateSync` e `TelemetrySnapshot`.

---

<!-- SPLIT:TASK-11.md -->
# Task ID: TASK-11 — App Android: Trackpad Virtual Multi-gesto e Teclado com Modificadores

## 1. Contexto Arquitetural
Quando o usuário está no sofá ou longe da mesa, ele precisa controlar o cursor e digitar no Linux com a mesma precisão de um touchpad de notebook, incluindo teclas especiais do Linux (`Super/Meta`, `Ctrl`, `Alt`, `Esc`, `Tab`, `F1-F12`).

## 2. Dependências
- `TASK-04`, `TASK-09`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/ui/trackpad/TrackpadScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/trackpad/GestureDetectorSurface.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/trackpad/ModifierKeysBar.kt`

## 4. Requisitos Técnicos
1. **Superfície Multi-gesto de Baixa Latência (`GestureDetectorSurface.kt`):**
   - Capturar eventos brutos de `pointerInput` no Compose e traduzi-los em mensagens `Envelope`:
     - **Arrastar com 1 dedo:** envia `MouseMoveEvent` relativo (`dx`, `dy`) aplicando curva de aceleração suave configurável.
     - **Toque simples (1 dedo):** envia `MouseButtonEvent(LEFT, CLICK)`.
     - **Toque com 2 dedos:** envia `MouseButtonEvent(RIGHT, CLICK)`.
     - **Toque com 3 dedos:** envia `MouseButtonEvent(MIDDLE, CLICK)`.
     - **Arrastar com 2 dedos (Vertical/Horizontal):** envia `MouseScrollEvent`.
     - **Duplo toque + segurar (Drag & Drop):** envia `MouseButtonEvent(LEFT, PRESS)` no início e `RELEASE` ao soltar o dedo.
2. **Barra de Modificadores "Sticky" e Teclado (`ModifierKeysBar.kt`):**
   - Criar uma barra superior/inferior com teclas de estado (`Ctrl`, `Alt`, `Super`, `Shift`) que podem ser "travadas" (toggle) com um toque para combinar com cliques de mouse ou com o teclado virtual do Android, além de botões diretos para `Esc`, `Tab`, `Enter`, `Setas` e atalhos de workspace (`Super+1..5`).

## 5. Restrições
- Agrupe/limite (coalesce) eventos de movimento do touch para a taxa de atualização da tela (60Hz/120Hz) sem saturar a fila de envio do WebSocket.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Compilação limpa e testes unitários da função de cálculo de delta/aceleração do trackpad e máquina de estados dos gestos de 1 e 2 dedos.

---

<!-- SPLIT:TASK-12.md -->
# Task ID: TASK-12 — App Android: Desktop Remoto WebRTC e Mapeamento de Toque

## 1. Contexto Arquitetural
Esta task implementa a visualização da tela do PC em tempo real no celular com áudio sincronizado (`TASK-08`), permitindo usar o PC remotamente de qualquer lugar (LAN, USB ou Tailscale).

## 2. Dependências
- `TASK-04`, `TASK-08`, `TASK-09`, `TASK-11`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/webrtc/WebRtcClientManager.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/remote/RemoteDesktopScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/remote/TouchCoordinateMapper.kt`

## 4. Requisitos Técnicos
1. **Cliente WebRTC (`WebRtcClientManager.kt`):**
   - Integrar `org.webrtc:google-webrtc` (ou `io.getstream:stream-webrtc-android`) com aceleração de hardware (`DefaultVideoDecoderFactory` via `EglBase`).
   - Enviar `WebRtcSignal{START_STREAM}` ao entrar na tela, receber o `SDP_OFFER` do daemon Linux, gerar o `SDP_ANSWER`, negociar ICE e renderizar a track de vídeo em um `SurfaceViewRenderer` dentro do Compose (`AndroidView`).
2. **Zoom/Pan e Mapeamento de Coordenadas (`TouchCoordinateMapper.kt` & `RemoteDesktopScreen.kt`):**
   - Oferecer 2 modos de interação selecionáveis em um menu flutuante:
     1. **Modo Toque Direto (Absoluto):** tocar em um ponto da imagem do monitor calcula a coordenada normalizada `norm_x, norm_y` $\in [0.0, 1.0]$ descontando barras pretas (*letterboxing*) e zoom/pan atual, enviando `MouseMoveEvent(is_absolute=true)` seguido de clique para o dispositivo `uinput` absoluto do Linux.
     2. **Modo Cursor Relativo (Trackpad sobre o Vídeo):** arrastar em qualquer lugar move o cursor relativamente sem precisar alcançar os cantos com o polegar.
   - Incluir a barra flutuante retrátil de teclas especiais (`ModifierKeysBar` da `TASK-11`) e botão para abrir o teclado virtual sobre o vídeo.

## 5. Restrições
- Sempre liberar `SurfaceViewRenderer.release()`, `PeerConnection.close()` e enviar `WebRtcSignal{STOP_STREAM}` no `DisposableEffect(Unit) { onDispose { ... } }` ao sair da tela.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Testes unitários para `TouchCoordinateMapper` validando que um toque dentro de uma view `1080x2400` exibindo um stream `1920x1080` com zoom `2.0x` produz exatamente as coordenadas normalizadas `(norm_x, norm_y)` esperadas dentro de `[0.0, 1.0]`.

---

<!-- SPLIT:TASK-13.md -->
# Task ID: TASK-13 — App Android: Emulador de Terminal Integrado (PTY) e Polimento Final

## 1. Contexto Arquitetural
Para garantir administração total do Linux mesmo se o servidor gráfico (Wayland/X11) travar ou estiver desligado, o aplicativo inclui uma aba de Terminal Interativo conectada diretamente ao stream `PTY` do daemon (`TASK-05`), além da navegação unificada entre os 4 modos do app (Deck, Trackpad, Desktop Remoto e Terminal).

## 2. Dependências
- `TASK-05`, `TASK-09`, `TASK-10`, `TASK-11`, `TASK-12`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/ui/terminal/TerminalScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/terminal/TerminalViewModel.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/navigation/MainScaffold.kt`
- `README.md` (Documentação completa de build, setup e uso do projeto)

## 4. Requisitos Técnicos
1. **Emulador de Terminal (`TerminalScreen.kt` & `TerminalViewModel.kt`):**
   - Ao abrir a tela, enviar `PtyOpenRequest` e sincronizar dimensões de colunas/linhas via `PtyResizeEvent`.
   - Renderizar sequências ANSI/VT100 e cores de 256 bits (via `xterm.js` empacotado localmente em `assets/` dentro de uma `WebView` de latência zero sem acesso externo à internet, ou canvas nativo de terminal).
   - Incluir barra de ferramentas de Sysadmin acima do teclado com teclas rápidas: `ESC`, `TAB`, `CTRL+C`, `CTRL+Z`, `CTRL+D`, `CTRL+L`, `Setas (↑ ↓ ← →)`, `|` (pipe), `/` e `-`.
2. **Navegação Unificada (`MainScaffold.kt`):**
   - Integrar na barra de navegação principal as 4 abas: **Deck**, **Trackpad**, **Tela Remota** e **Terminal**, mais o indicador de status da conexão mTLS/latência no topo.
3. **Documentação de Produção (`README.md`):**
   - Documentar o passo a passo completo: instalação de dependências no Linux, configuração das permissões `udev`, execução do daemon (ou serviço `systemd --user`), pareamento via QR Code, uso via Wi-Fi/Tailscale e uso via cabo USB (`adb reverse`).

## 5. Restrições
- Quaisquer assets do terminal (`xterm.js`) devem ser servidos 100% offline/localmente sem depender de CDN na internet.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Compilação completa tanto do `linux-daemon` (`go build ./...` e `go test ./...`) quanto do `android-client`.
2. Verificar que todas as rotas do `MainScaffold.kt` estão conectadas e que o `README.md` documenta todos os comandos reais criados ao longo das 13 tasks.