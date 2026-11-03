# FIX-03 — Substituição da Tela Remota WebRTC por Stream Contínuo Persistente

## 1. Diagnóstico Técnico do Bug
Nos relatórios v1 e v2, a Tela Remota ficou completamente inoperante, presa em loop na mensagem *"Iniciar desktop remoto WebRTC"*.
* **Causa Raiz:**
  1. A negociação WebRTC (SDP Offer/Answer + STUN/ICE + DTLS) somada às permissões assíncronas do `xdg-desktop-portal` no Wayland trava sempre que o candidato ICE falha no túnel USB/LAN ou quando o encoder H.264 de hardware não inicializa no primeiro segundo.
  2. Tentativas de fallback que invocam um novo processo de screenshot (`grim`/`import`) a cada frame causam overhead massivo de CPU (`fork/exec` $30$ vezes por segundo) e atrasam todo o daemon.

## 2. Objetivo da Correção
Substituir o complexo pipeline WebRTC por um **Stream Contínuo HTTP Multipart (`GET :8444/screen.mjpg`) alimentado por um único processo persistente de captura**, com auto-detecção de ambiente gráfico (Wayland / X11) e decodificação otimizada em `RGB_565` no Android.

---

## 3. Escopo e Arquivos-Alvo

* **Daemon Linux (Go):**
  * `linux-daemon/internal/capture/mjpeg_server.go` (Novo)
  * `linux-daemon/internal/capture/mjpeg_server_test.go` (Novo)
  * `linux-daemon/cmd/daemon/main.go` (Expor `/screen.mjpg` na porta `:8444`)
* **Cliente Android (Kotlin):**
  * `android-client/app/src/main/java/com/linuxdeck/client/ui/remote/RemoteDesktopScreen.kt` (Substituir o cliente WebRTC pelo leitor de stream contínuo MJPEG integrado ao `UdpFastControlClient`)

---

## 4. Especificação Técnica Passo a Passo

### 4.1. Servidor de Stream Persistente no Daemon (`mjpeg_server.go`)
1. Ao receber uma requisição `GET /screen.mjpg`:
   * Configurar os headers HTTP para streaming sem buffer:
     * `Content-Type: multipart/x-mixed-replace; boundary=frame`
     * `Cache-Control: no-store, no-cache, must-revalidate, max-age=0`
     * `Connection: close`
2. **Ordem de Auto-Detecção do Comando Persistente de Captura (Executa apenas 1 processo enquanto o cliente estiver conectado):**
   * **Opção 1 (Wayland / PipeWire com GStreamer):**
     ```bash
     gst-launch-1.0 -q pipewiresrc do-timestamp=true ! videoconvert ! videoscale ! videorate ! image/jpeg,width=1280,height=720,framerate=25/1,quality=70 ! multipartmux boundary=frame ! fdsink fd=1
     ```
   * **Opção 2 (Sessão X11 ou XWayland com FFmpeg):**
     ```bash
     ffmpeg -loglevel quiet -f x11grab -framerate 25 -i ${DISPLAY:-:0} -vf scale=1280:-1 -q:v 7 -f mpjpeg -boundary_tag frame -
     ```
   * **Opção 3 (Wayland wlroots / Hyprland / Sway via `wf-recorder` + `ffmpeg` pipe):**
     ```bash
     wf-recorder -c rawvideo -m avi -f pipe:1 | ffmpeg -loglevel quiet -i pipe:0 -vf scale=1280:-1 -q:v 7 -f mpjpeg -boundary_tag frame -
     ```
   * **Opção 4 (Fallback Universal / Modo de Teste Headless):**
     Se os pipelines anteriores terminarem com erro nos primeiros $500\text{ ms}$ (ou se `DECK_CAPTURE_TEST_SOURCE=1`), iniciar um loop em Go controlado por `time.NewTicker(66 * time.Millisecond)` ($15\text{ FPS}$) usando a ferramenta disponível (`grim -t jpeg -q 65 -` no Wayland ou gerador sintético em memória) escrevendo os delimitadores `--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ...\r\n\r\n` no `http.ResponseWriter` seguidos de `flusher.Flush()`.
3. Quando o cliente Android fechar a tela (`r.Context().Done()`), matar o processo de captura imediatamente via `cmd.Process.Kill()`.

### 4.2. Visualizador Android de Baixo Consumo de Memória (`RemoteDesktopScreen.kt`)
1. Remover o loop de tentativa de conexão WebRTC que travava a tela.
2. Em um `LaunchedEffect(streamUrl)` rodando em `Dispatchers.IO`:
   * Abrir `HttpURLConnection` para `http://<host>:8444/screen.mjpg`.
   * Ler o `BufferedInputStream` procurando os marcadores binários do JPEG:
     * Início de imagem (**SOI**): `0xFF 0xD8`
     * Fim de imagem (**EOI**): `0xFF 0xD9`
   * Decodificar os bytes do frame usando `BitmapFactory.decodeByteArray` com:
     ```kotlin
     val options = BitmapFactory.Options().apply {
         inPreferredConfig = Bitmap.Config.RGB_565 // Corta o uso de RAM pela metade (2 bytes/pixel)
         inMutable = true
     }
     ```
   * Publicar o `ImageBitmap` para a UI. Se a conexão cair, aguardar $1000\text{ ms}$ e reconectar automaticamente, exibindo um botão manual de **"Reconectar Agora"** caso o servidor retorne erro.
3. **Mapeamento de Toque via UDP Fast-Lane (`FIX-01`):**
   * Quando o usuário tocar ou arrastar sobre a imagem do desktop remoto, calcular as coordenadas normalizadas:
     $$\text{normX} = \text{clamp}\left(\frac{x_{\text{toque}}}{L_{\text{view}}}, 0.0, 1.0\right), \quad \text{normY} = \text{clamp}\left(\frac{y_{\text{toque}}}{A_{\text{view}}}, 0.0, 1.0\right)$$
   * Enviar imediatamente via `udpClient.sendMouseAbsolute(normX, normY)` e `udpClient.sendMouseButton(...)` pela porta `:8443/udp`.

---

## 5. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && DECK_CAPTURE_TEST_SOURCE=1 go test -v ./internal/capture/...`.
2. O teste deve fazer uma requisição HTTP `GET /screen.mjpg`, ler os primeiros $3$ frames JPEG completos validando os bytes `0xFFD8` e `0xFFD9`, cancelar a requisição e confirmar que o processo/goroutine de captura encerrou em menos de $100\text{ ms}$.