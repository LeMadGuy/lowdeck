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

