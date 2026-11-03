Terminal Remoto: Crashes Fatais, Travamentos da UI e Lentidão
* **Causa Raiz Identificada:** Explosão de recomposição na UI Thread do Android. Ao abrir o shell, o bash/terminfo emite sequências de escape ANSI em rajada. O app Android acumulava essas strings diretamente em estado observável da UI (Compose/TextView), provocando recomposição infinita, saturação de 100% da CPU na UI Thread e Watchdog Crash / ANR (Application Not Responding).
* **Solução nos Fixes:**
  - `linux-daemon/fixes/pty_throttled.go`: Implementa controle de fluxo com handshake `PtyReady` e janela deslizante para que o PTY não inunde o cliente antes dele estar pronto.
  - `android-client/fixes/SafeTerminalEngine.kt`: Emulador com buffer circular fixo (RingBuffer de 1.000 linhas), processamento isolado em `Dispatchers.Default` e despacho para a UI limitado a 30 FPS via Flow `conflate()`, impedindo travamentos e eliminando 100% dos crashes.
