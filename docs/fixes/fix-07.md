# FIX-07: Auto-Release de Teclas/Botões e Ciclo de Vida Limpo do uinput

## 1. Contexto do Bug e Diagnóstico
Ao desconectar ou encerrar o aplicativo cliente Android, o mouse e o teclado físicos conectados diretamente ao PC passam a apresentar travamentos, teclas modificadoras que parecem bloqueadas (ex: `Ctrl`, `Alt`, `Shift` ou clique do mouse preso) ou recusa parcial de comandos.

### Causa Raiz
No Linux, o subsistema `/dev/uinput` gerencia dispositivos virtuais de hardware (`evdev`). Se um evento de pressionamento de tecla ou clique de mouse (`EV_KEY` com valor `1`) for emitido pelo daemon, mas a conexão com o cliente Android cair de forma abrupta antes que o evento de liberação (`EV_KEY` com valor `0`) seja processado:
1. O Kernel Linux considera que a tecla física ou botão virtual continua mantido pressionado continuamente (*stuck key leak*).
2. Teclas modificadoras virtuais presas (como `KEY_LEFTCTRL`, `KEY_LEFTALT` ou `KEY_LEFTMETA`) sequestram atalhos de todo o ambiente gráfico (Wayland/X11), fazendo o teclado físico parecer quebrado ou digitar atalhos inesperados.
3. Se o descritor `/dev/uinput` for fechado repentinamente sem `UI_DEV_DESTROY` e sem sincronização (`EV_SYN`), o nó virtual sob `/dev/input/eventX` pode reter o estado de botões no compositor do sistema operacional.

---

## 2. Arquivos-Alvo para Modificação
- `linux-daemon/internal/input/uinput_linux.go`
- `linux-daemon/internal/input/async_injector.go`
- `linux-daemon/cmd/daemon/main.go`
- `linux-daemon/internal/input/handler.go`
- `linux-daemon/internal/input/keymap.go`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/remote/RemoteDesktopScreen.kt`

---

## 3. Implementação da Solução Técnica

### A. Adicionar Mecanismo `ReleaseAll()` Completo no Driver `uinput`
1. Rastreamento com watchdog de auto-release (5s) para botões de mouse (`pressedButtons`).
2. Liberação de todas as teclas e botões rastreados (`pressedKeys` e `pressedButtons`).
3. Varredura e liberação forçada de teclas modificadoras críticas (`stuckKeyCodes`: `KEY_LEFTCTRL`, `KEY_RIGHTCTRL`, `KEY_LEFTSHIFT`, `KEY_RIGHTSHIFT`, `KEY_LEFTALT`, `KEY_RIGHTALT`, `KEY_LEFTMETA`, `KEY_RIGHTMETA`, `KEY_CAPSLOCK`).
4. Varredura e liberação forçada de botões de mouse (`stuckMouseButtons`: `BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`, `BTN_SIDE`, `BTN_EXTRA`) e touch (`stuckTouchButtons`: `BTN_TOUCH`, `BTN_LEFT`).
5. Envio de evento de sincronização `EV_SYN / SYN_REPORT` para cada dispositivo virtual (`keyboard`, `relMouse`, `absTouch`).
6. Execução obrigatória de `ReleaseAll()` dentro do método `Close()` antes de invocar `destroyDevice` (`UI_DEV_DESTROY`).

### B. Interceptar Quedas de Conexão no Daemon (`main.go`)
No callback `wsSrv.OnDisconnect(func(client server.ClientSession) { ... })`, invocar `inputInjector.ReleaseAll()` imediatamente, esvaziando a fila assíncrona antes e sincronizando com o Kernel Linux.

### C. Watchdog de Auto-Release por Timeout
Adicionado watchdog `time.AfterFunc` com timeout de 5 segundos para botões de mouse pressionados sem liberação subsequente, evitando bloqueio do mouse físico do host caso o app seja suspenso ou backgrounded.
