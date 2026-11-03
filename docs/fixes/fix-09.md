# FIX-02 — Substituição do Terminal por Motor `xterm.js` + WebSocket Binário Puro

## 1. Diagnóstico Técnico do Bug
Nos testes da v1 e da v2, o Terminal Remoto apresentou lentidão severa, demorava para carregar e **causava um crash fatal fechando o aplicativo Android logo após terminar de carregar**.
* **Causa Raiz:**
  1. Quando o shell (`bash`/`zsh`) inicia, ele emite milhares de bytes contendo sequências complexas de controle VT100/ANSI (posicionamento de matriz, cores 24-bit, títulos de janela) e caracteres UTF-8 multibyte que frequentemente são divididos no meio de um `read()` de $4096\text{ bytes}$.
  2. Trafegar esses blocos dentro de mensagens Protobuf ou tentar processá-los manualmente em um `Text()` / `LazyColumn` do Jetpack Compose trava a **Main Thread (UI Thread)** do Android com cálculos de layout de fonte e causa exceções de UTF-8 inválido ou estouro de memória/ANR.

## 2. Objetivo da Correção
Isolar 100% o terminal do canal Protobuf e da árvore de texto do Jetpack Compose:
1. O Daemon Linux passará a expor na porta `:8444` um endpoint WebSocket binário puro (`GET /ws/pty`) ligado diretamente ao descritor `/dev/ptmx` (`creack/pty`) e um endpoint HTML local (`GET /terminal`) contendo o motor **`xterm.js`** com renderização em Canvas HTML5.
2. O App Android substituirá o componente de texto nativo por uma `WebView` isolada acelerada por hardware conectada a `:8444/terminal`, acompanhada de uma barra nativa em Compose para teclas especiais (`ESC`, `TAB`, `CTRL+C`, `CTRL+D`, setas).

---

## 3. Escopo e Arquivos-Alvo

* **Daemon Linux (Go):**
  * `linux-daemon/internal/pty/web_terminal.go` (Novo ou substitui o handler antigo)
  * `linux-daemon/internal/pty/xterm_assets.go` (Assets HTML/JS embutidos para funcionamento offline/LAN)
  * `linux-daemon/internal/pty/web_terminal_test.go` (Novo)
  * `linux-daemon/cmd/daemon/main.go` (Registrar rotas `/terminal` e `/ws/pty` no servidor HTTP `:8444`)
* **Cliente Android (Kotlin):**
  * `android-client/app/src/main/java/com/linuxdeck/client/ui/terminal/TerminalScreen.kt` (Reescrever usando `AndroidView` + `WebView` + Barra de Atalhos)
  * Remover/desativar o processamento de `PtyDataEvent` na UI Thread do Compose.

---

## 4. Especificação Técnica Passo a Passo

### 4.1. Servidor WebSocket Binário de PTY no Daemon (`web_terminal.go`)
1. Ao receber conexão WebSocket em `/ws/pty`:
   * Iniciar o shell padrão do usuário (`$SHELL` ou `/bin/bash`) via `pty.StartWithSize(cmd, &pty.Winsize{Cols: 80, Rows: 24})` com `TERM=xterm-256color`.
2. **Protocolo de Frames Binários no `/ws/pty`:**
   * **Daemon $\rightarrow$ Cliente (`websocket.BinaryMessage`):** Enviar os bytes brutos lidos do PTY agrupados em janelas de até $12\text{ ms}$ ou $8192\text{ bytes}$ (evita milhares de micro-frames por segundo durante um `cat` ou `dmesg`).
   * **Cliente $\rightarrow$ Daemon (`websocket.BinaryMessage`):**
     * Se o primeiro byte for `0x00`: o restante do slice (`msg[1:]`) é escrito diretamente no `ptmx.Write()`.
     * Se o primeiro byte for `0x01`: o restante do slice (`msg[1:]`) é um JSON `{"cols": 100, "rows": 30}` que aciona imediatamente `pty.Setsize(ptmx, &pty.Winsize{Cols: cols, Rows: rows})`.
3. **Limpeza Garantida (`defer`):**
   * Quando o WebSocket desconectar, fechar o `ptmx` e enviar `syscall.SIGKILL` ao processo do shell para nunca deixar processos órfãos.

### 4.2. Página `xterm.js` Embutida (`GET /terminal`)
1. Servir uma página HTML com fundo `#0D1117`, margem zero (`overflow: hidden`) que instancia `new Terminal({ cursorBlink: true, fontSize: 13, fontFamily: 'monospace', scrollback: 1500 })` e `FitAddon`.
2. Incluir um fallback nativo em `<canvas>` ou embutir o script do `xterm.js` diretamente no binário Go (`//go:embed`) para que o terminal funcione **mesmo quando o PC Linux e o celular estiverem sem acesso à internet (cabo USB offline)**.
3. Expor no `window` duas funções JavaScript para a ponte com o Android:
   * `window.sendRawBase64(b64String)`: decodifica e envia bytes para o PTY com o prefixo `0x00`.
   * `window.sendSpecialSeq(seqString)`: envia sequências ANSI (como `\x03` para `CTRL+C`, `\x1b` para `ESC`, `\x1b[A` para Seta Acima) diretamente para o socket.

### 4.3. Tela de Terminal no Android (`TerminalScreen.kt`)
1. Construir um `Column` no Jetpack Compose contendo:
   * No topo/base: Barra horizontal de botões nativos (`ESC`, `TAB`, `CTRL+C`, `CTRL+Z`, `CTRL+D`, `CTRL+L`, `↑`, `↓`, `←`, `→`, `/`, `-`, `|`). Ao clicar, chama `webView.evaluateJavascript("window.sendSpecialSeq(...)", null)`.
   * No centro (`Modifier.weight(1f)`): `AndroidView` encapsulando uma `WebView` com `javaScriptEnabled = true`, `domStorageEnabled = true` e `setLayerType(View.LAYER_TYPE_HARDWARE, null)` carregando `http://<host>:8444/terminal`.
2. No `DisposableEffect(Unit) { onDispose { ... } }`, chamar `webView.loadUrl("about:blank")` e `webView.destroy()` para encerrar o WebSocket e matar o processo PTY no Linux assim que o usuário fechar o terminal.

---

## 5. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/pty/...`.
2. O teste automatizado deve conectar um cliente WebSocket em `/ws/pty`, enviar um frame de resize `[0x01]{"cols":120,"rows":40}`, enviar `[0x00]echo "LOWDECK_OK"\n`, ler os frames binários retornados e confirmar a presença de `"LOWDECK_OK"` sem erros.
3. No Android, confirmar que nenhum dado de terminal transita pelo `StateFlow` do Jetpack Compose.