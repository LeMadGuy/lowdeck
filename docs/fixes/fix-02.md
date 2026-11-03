**FIX-02: Terminal Remoto (Crash e Latência Extrema)**
   - PTY em modo não-bloqueante (`O_NONBLOCK`) no Linux.
   - Buffering de saída de 8ms-16ms para evitar saturar o canal de rede com bytes avulsos.
   - Isolamento de threads no Android (Coroutine com `Dispatchers.Default`) protegendo o emulador de terminal contra colisões na UI Thread.
