**FIX-01: Latência e Cortes no Trackpad / Conexão Estável no Cabo**
   - Ativação obrigatória de `TCP_NODELAY` nas ligações TCP/WebSocket.
   - Batching/Coalescência de eventos de toque no Android sincronizado com a taxa de atualização do ecrã (Choreographer / 60-120Hz).
   - Canal assíncrono com buffer de descarte no daemon Linux para evitar bloqueio em `/dev/uinput`.