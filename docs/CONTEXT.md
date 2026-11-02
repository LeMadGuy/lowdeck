# CONTEXT.md — Guia Arquitetural e Memória Global para Agentes

> **Propósito deste documento:** Este arquivo é a fonte primária de contexto do monorepo **Linux Deck & Remote Control**. Todo agente de IA deve ler este documento antes de iniciar qualquer `TASK-XX` localizada em `docs/tasks/`.
>
> **Integração com Ferramentas de Agentes:** Você pode criar symlinks deste arquivo na raiz do repositório (`ln -s CONTEXT.md CLAUDE.md`, `ln -s CONTEXT.md AGENTS.md` ou `.cursorrules`) para carregamento automático de contexto.

---

## 1. Visão Geral do Sistema

O **Linux Deck & Remote Control** é uma plataforma de controle remoto de acesso total e *Macro Deck* de baixíssima latência entre um cliente **Android** e um host **Linux** (compatível com Wayland, X11 e TTY).

O sistema opera sob quatro modos integrados no mesmo aplicativo e daemon:
1. **Macro Deck Dinâmico:** Grid configurável de botões com execução assíncrona de scripts/comandos, atalhos de teclado, controle de mídia/energia e *widgets* de telemetria ao vivo (CPU, RAM, Temperatura e Rede).
2. **Trackpad & Teclado Virtual:** Injeção de eventos de mouse e teclado diretamente no Kernel Linux via `/dev/uinput`, contornando bloqueios de compositores Wayland e funcionando inclusive na tela de login (SDDM/GDM) e TTY.
3. **Desktop Remoto em Tempo Real:** Captura de vídeo via PipeWire (`xdg-desktop-portal` ScreenCast) e áudio do sistema, codificados por hardware via GStreamer (H.264 *zero-latency* + Opus) e transmitidos via **WebRTC** (`pion/webrtc`), com mapeamento de toque absoluto e relativo.
4. **Terminal Interativo (PTY):** Emulador de terminal completo conectado a sessões `PTY` reais no host Linux sobre o canal seguro.

---

## 2. Stack Tecnológica e Decisões de Engenharia

| Camada | Tecnologia / Biblioteca | Justificativa Técnica |
| :--- | :--- | :--- |
| **Contrato de Dados** | **Protocol Buffers v3 (`proto3`)** + **JSON Schema** | Contrato único tipado (`Envelope` com `oneof payload`). Impede divergência de payloads entre Go e Kotlin. |
| **Segurança & Rede** | **mTLS 1.3 (X.509 ECDSA)** + **WebSockets Binários** + **mDNS** | Como o daemon expõe shell `PTY` e `/dev/uinput`, nenhuma conexão sem certificado cliente assinado e pareado via QR Code é aceita. |
| **Linux Daemon (Host)** | **Go (Golang 1.22+)** | Compilação instantânea, binário único estático, concorrência nativa (`goroutines`/`channels`) e ecossistema maduro (`pion/webrtc`, `creack/pty`, `godbus/dbus`). |
| **Injeção de Input** | **`/dev/uinput` (`evdev` Kernel)** | Cria dispositivos virtuais reais no Kernel Linux (Mouse Relativo, TouchScreen Absoluto $0..65535$ e Teclado Completo). |
| **Captura & Streaming** | **PipeWire + GStreamer + `pion/webrtc/v4`** | Captura zero-copy em Wayland/X11, codificação H.264 sem B-frames (VA-API / NVENC / `x264enc`) e transporte UDP/SRTP adaptativo. |
| **Android Client** | **Kotlin + Jetpack Compose (Material 3)** | UI declarativa reativa, `ForegroundService` persistente, `OkHttp` com mTLS e renderização WebRTC acelerada por hardware (`SurfaceViewRenderer` / `EglBase`). |

---

## 3. Mapa de Diretórios e Módulos (Alinhado às `TASK_SPECS`)

* **Módulo Go:** `github.com/local/linux-deck-daemon` (em `linux-daemon/go.mod`)
* **Pacote Android:** `com.linuxdeck.client` (em `android-client/app/`)

```text
linux-deck-remote/
├── CONTEXT.md                          # Este arquivo (Contexto Global dos Agentes)
├── README.md                           # Documentação de setup, build e operação
├── proto/
│   └── deck/v1/
│       ├── deck.proto                  # Fonte única da verdade do protocolo (Envelope + Payloads)
│       └── profile_schema.json         # JSON Schema dos perfis de botões e widgets do Deck
├── scripts/
│   ├── gen-proto.sh                    # Compila deck.proto para Go e valida schemas
│   ├── setup-permissions.sh            # Instala regras udev e adiciona usuário ao grupo 'input'
│   └── udev/
│       └── 99-deck-uinput.rules        # Permissão 0660 no /dev/uinput para o grupo 'input'
├── docs/
│   └── tasks/
│       ├── TASK_SPECS.md               # Catálogo mestre das 13 tasks
│       └── TASK-01.md .. TASK-13.md    # Especificações individuais para cada sessão de agente
├── linux-daemon/
│   ├── go.mod
│   ├── cmd/
│   │   ├── daemon/main.go              # Entrypoint do servidor Linux
│   │   └── mock-client/main.go         # CLI de teste para validação autônoma pelo agente
│   ├── pkg/
│   │   └── protocol/v1/                # Código Go gerado automaticamente pelo protoc (NÃO EDITAR)
│   └── internal/
│       ├── auth/                       # TASK-02: CA local, certificados X.509, mTLS 1.3 e QR Code
│       ├── discovery/                  # TASK-03: Anúncio mDNS (_linuxdeck._tcp)
│       ├── server/                     # TASK-03: Servidor WebSocket seguro e Router modular
│       ├── input/                      # TASK-04: Dispositivos /dev/uinput (Mouse Rel/Abs e Teclado)
│       ├── macro/                      # TASK-05: Engine de execução de macros e perfis JSON
│       ├── pty/                        # TASK-05: Gerenciador de sessões de terminal PTY
│       ├── telemetry/                  # TASK-06: Coletores nativos (/proc/stat, /proc/meminfo, /sys)
│       ├── dbus/                       # TASK-06: Controle de mídia (MPRIS2) e sessão (logind)
│       ├── capture/                    # TASK-07: Portal ScreenCast + PipeWire + GStreamer
│       └── webrtc/                     # TASK-08: PeerConnection Pion WebRTC e sinalização SDP/ICE
└── android-client/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts            # Gera classes Protobuf direto de ../../proto/deck/v1
        └── src/main/java/com/linuxdeck/client/
            ├── network/                # TASK-09: MdnsDiscoveryManager e DeckConnectionService
            ├── security/               # TASK-09: MtlsKeyManager (KeyStore + Certificate Pinning)
            ├── webrtc/                 # TASK-12: WebRtcClientManager
            └── ui/
                ├── pairing/            # TASK-09: Scanner QR Code e pareamento mTLS
                ├── deck/               # TASK-10: Grid dinâmico do Deck, botões e widgets ao vivo
                ├── trackpad/           # TASK-11: Superfície multi-gesto e barra de modificadores
                ├── remote/             # TASK-12: Tela WebRTC e TouchCoordinateMapper
                ├── terminal/           # TASK-13: Emulador de terminal PTY (xterm.js offline)
                └── navigation/         # TASK-13: MainScaffold unificando as 4 abas
```

---

## 4. Invariantes e Regras de Ouro para Agentes (Guardrails)

Todo agente que escrever ou modificar código neste repositório **DEVE** obedecer às 6 regras abaixo:

1. **Contract-First Estrito:**
   * **Nunca** edite manualmente arquivos gerados dentro de `linux-daemon/pkg/protocol/v1/`.
   * Se precisar de um novo campo ou mensagem, altere `proto/deck/v1/deck.proto` e execute `./scripts/gen-proto.sh`.

2. **Isolamento de Hardware e Modo Degradado para Testes (`Mock/Fallback`):**
   * Subsistemas que dependem de permissões ou sessão gráfica do Linux (`/dev/uinput`, `D-Bus`, `PipeWire`) **devem** ser abstraídos por interfaces Go (`InputInjector`, `MediaSource`, etc.).
   * Caso o teste rode em um container, CI ou terminal sem `/dev/uinput` ou sem monitor Wayland ativo, o pacote deve usar automaticamente o fallback/mock ou a fonte sintética (`DECK_CAPTURE_TEST_SOURCE=1`) para que `go test ./...` nunca falhe por falta de hardware.

3. **Validação CLI-First (`cmd/mock-client`):**
   * Toda nova funcionalidade adicionada ao `linux-daemon` deve ganhar primeiro um subcomando correspondente em `linux-daemon/cmd/mock-client/main.go` e um teste automatizado em `_test.go`.

4. **Prevenção de Vazamento de Recursos (Lifecycle Rigoroso):**
   * Dispositivos virtuais `/dev/uinput`, processos filhos `PTY` e pipelines `GStreamer`/`WebRTC` **devem** ser destruídos imediatamente quando o `context.Context` da sessão cancelar ou o daemon receber `SIGINT`/`SIGTERM`.
   * Nunca bloqueie a loop de leitura do WebSocket esperando um comando de macro terminar; dispare execuções em goroutines dedicadas com `context.WithTimeout` e escrita protegida no canal de envio (`sendCh`).

5. **Segurança por Padrão:**
   * Chaves privadas (`.key`) e a store de clientes confiáveis (`trusted_clients.json`) devem ser criadas com permissão `0600` (diretórios com `0700`).
   * Conexões WebSocket para o canal de controle usam exclusivamente `BinaryMessage` com `proto.Marshal` / `proto.Unmarshal` sobre TLS 1.3 mútuo.

6. **Escopo Cirúrgico por Task:**
   * Modifique apenas os arquivos listados na seção **"Escopo Exclusivo e Arquivos-Alvo"** da task atual em `docs/tasks/TASK-XX.md`.

---

## 5. Fluxo de Trabalho Padrão do Agente por Sessão

Ao receber a instrução `"Execute a TASK-XX"`, siga este roteiro passo a passo:

1. **Leitura:** Leia este `CONTEXT.md` e a especificação completa em `docs/tasks/TASK-XX.md`.
2. **Verificação de Pré-requisitos:** Confirme que as tasks listadas em *Dependências* já estão marcadas como concluídas na tabela da Seção 7 abaixo.
3. **Implementação:** Escreva o código limpo, modular e os testes unitários/integração exigidos.
4. **Auto-Validação (Obrigatória antes de concluir):**
   * Para tasks de Protocolo (`TASK-01`):
     ```bash
     ./scripts/gen-proto.sh
     ```
   * Para tasks do Daemon Linux (`TASK-01` a `TASK-08`):
     ```bash
     cd linux-daemon
     go build ./...
     go test -v -race ./...
     ```
   * Para tasks do Cliente Android (`TASK-09` a `TASK-13`):
     ```bash
     cd android-client
     ./gradlew testDebugUnitTest assembleDebug
     ```
5. **Atualização de Estado:** Marque a task como `[x] Concluída` na tabela da Seção 7 deste `CONTEXT.md` e registre eventuais notas técnicas relevantes para a próxima task.

---

## 6. Convenções de Código

* **Go (`linux-daemon`):**
  * Use `context.Context` como primeiro parâmetro em todas as funções de I/O, execução de processos ou rede.
  * Trate e envolva erros com contexto (`fmt.Errorf("input uinput init: %w", err)`).
  * Use `log/slog` da biblioteca padrão para logs estruturados.
* **Kotlin (`android-client`):**
  * Arquitetura **MVVM Unidirecional** (`StateFlow` para estado de UI, `SharedFlow` para eventos únicos).
  * Operações de socket, Protobuf e criptografia sempre em `Dispatchers.IO`.
  * Em Jetpack Compose, isole o estado de telemetria (`TelemetrySnapshot`) para evitar recomposição desnecessária do grid estático de botões do Deck.

---

## 7. Rastreamento de Progresso das Tasks (`Task Tracker`)

Os agentes devem atualizar o status abaixo ao finalizar e validar cada etapa:

| ID | Título da Task | Dependências | Status |
| :--- | :--- | :--- | :--- |
| **TASK-01** | Contrato do Protocolo (Protobuf) e Geração de Código | Nenhuma | `[x] Concluída` |
| **TASK-02** | Autenticação mTLS 1.3, Certificados X.509 e Pareamento QR | `TASK-01` | `[x] Concluída` |
| **TASK-03** | Descoberta mDNS e Servidor WebSocket/Protobuf Seguro | `TASK-01`, `TASK-02` | `[x] Concluída` |
| **TASK-04** | Subsistema `/dev/uinput` (Mouse Rel/Abs e Teclado Virtual) | `TASK-01`, `TASK-03` | `[x] Concluída` |
| **TASK-05** | Engine de Macros (Deck) e Terminal Interativo PTY | `TASK-01`, `TASK-03` | `[x] Concluída` |
| **TASK-06** | Telemetria (`/proc`, `/sys`) e Integração D-Bus (`MPRIS2`/`logind`) | `TASK-01`, `TASK-03` | `[x] Concluída` |
| **TASK-07** | Captura de Tela e Áudio de Baixa Latência (PipeWire + GStreamer) | `TASK-01`, `TASK-06` | `[x] Concluída` |
| **TASK-08** | Integração WebRTC no Daemon (`pion/webrtc`) e Sinalização | `TASK-01`, `TASK-03`, `TASK-07` | `[x] Concluída` |
| **TASK-09** | Android: Setup Gradle, Descoberta mDNS, Scanner QR e mTLS | `TASK-01` a `TASK-03` | `[ ] Pendente` |
| **TASK-10** | Android: UI do Macro Deck Dinâmico e Widgets de Telemetria | `TASK-05`, `TASK-06`, `TASK-09` | `[ ] Pendente` |
| **TASK-11** | Android: Trackpad Virtual Multi-gesto e Barra de Modificadores | `TASK-04`, `TASK-09` | `[ ] Pendente` |
| **TASK-12** | Android: Desktop Remoto WebRTC e Mapeamento de Toque | `TASK-04`, `TASK-08`, `TASK-09`, `TASK-11` | `[ ] Pendente` |
| **TASK-13** | Android: Emulador de Terminal Integrado (PTY) e Polimento Final | `TASK-05`, `TASK-09` a `TASK-12` | `[ ] Pendente` |