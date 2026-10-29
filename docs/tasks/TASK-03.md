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

