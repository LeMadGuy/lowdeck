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

