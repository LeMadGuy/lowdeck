Tela Remota: Loop Infinito "Iniciando Desktop Remoto WebRTC"
* **Causa Raiz Identificada:** 
  1. No WebRTC local (Wi-Fi ou Cabo USB/ADB), o cliente/servidor tentava resolver servidores STUN públicos (ex: `stun.l.google.com`). Sem conexão com a internet ou em rede fechada, a resolução DNS dava timeout de até 30s.
  2. Falta de negociação de candidatos ICE locais (Host Candidates) e congelamento na autorização do portal Wayland (`xdg-desktop-portal`).
* **Solução nos Fixes:**
  - `linux-daemon/fixes/webrtc_signaling_loop_fix.go`: Configura WebRTC local sem dependência de STUN público, timeout estrito de negociação de 3 segundos e fallback automático.
  - `linux-daemon/fixes/screen_jpeg_fallback.go`: Pipeline de fallback imediato: se o WebRTC demorar mais de 3 segundos para conectar, o daemon inicia streaming de frames JPEG comprimidos via canal de dados existente. A tela tem sinal de vida garantido em menos de 1 segundo!
  - `android-client/fixes/RemoteScreenViewer.kt`: Componente Android que aceita WebRTC nativo com comutação transparente para o fallback de frames caso o WebRTC não conclua o handshake.
