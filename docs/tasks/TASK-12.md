# Task ID: TASK-12 — App Android: Desktop Remoto WebRTC e Mapeamento de Toque

## 1. Contexto Arquitetural
Esta task implementa a visualização da tela do PC em tempo real no celular com áudio sincronizado (`TASK-08`), permitindo usar o PC remotamente de qualquer lugar (LAN, USB ou Tailscale).

## 2. Dependências
- `TASK-04`, `TASK-08`, `TASK-09`, `TASK-11`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/webrtc/WebRtcClientManager.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/remote/RemoteDesktopScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/remote/TouchCoordinateMapper.kt`

## 4. Requisitos Técnicos
1. **Cliente WebRTC (`WebRtcClientManager.kt`):**
   - Integrar `org.webrtc:google-webrtc` (ou `io.getstream:stream-webrtc-android`) com aceleração de hardware (`DefaultVideoDecoderFactory` via `EglBase`).
   - Enviar `WebRtcSignal{START_STREAM}` ao entrar na tela, receber o `SDP_OFFER` do daemon Linux, gerar o `SDP_ANSWER`, negociar ICE e renderizar a track de vídeo em um `SurfaceViewRenderer` dentro do Compose (`AndroidView`).
2. **Zoom/Pan e Mapeamento de Coordenadas (`TouchCoordinateMapper.kt` & `RemoteDesktopScreen.kt`):**
   - Oferecer 2 modos de interação selecionáveis em um menu flutuante:
     1. **Modo Toque Direto (Absoluto):** tocar em um ponto da imagem do monitor calcula a coordenada normalizada `norm_x, norm_y` $\in [0.0, 1.0]$ descontando barras pretas (*letterboxing*) e zoom/pan atual, enviando `MouseMoveEvent(is_absolute=true)` seguido de clique para o dispositivo `uinput` absoluto do Linux.
     2. **Modo Cursor Relativo (Trackpad sobre o Vídeo):** arrastar em qualquer lugar move o cursor relativamente sem precisar alcançar os cantos com o polegar.
   - Incluir a barra flutuante retrátil de teclas especiais (`ModifierKeysBar` da `TASK-11`) e botão para abrir o teclado virtual sobre o vídeo.

## 5. Restrições
- Sempre liberar `SurfaceViewRenderer.release()`, `PeerConnection.close()` e enviar `WebRtcSignal{STOP_STREAM}` no `DisposableEffect(Unit) { onDispose { ... } }` ao sair da tela.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Testes unitários para `TouchCoordinateMapper` validando que um toque dentro de uma view `1080x2400` exibindo um stream `1920x1080` com zoom `2.0x` produz exatamente as coordenadas normalizadas `(norm_x, norm_y)` esperadas dentro de `[0.0, 1.0]`.

---

