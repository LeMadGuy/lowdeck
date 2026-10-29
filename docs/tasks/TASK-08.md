# Task ID: TASK-08 — Integração WebRTC no Daemon (`pion/webrtc`) e Sinalização

## 1. Contexto Arquitetural
Com o canal de captura H.264/Opus pronto (`TASK-07`), precisamos transmiti-lo em tempo real via UDP/SRTP usando **WebRTC** (`github.com/pion/webrtc/v4`). A sinalização (troca de SDP Offer/Answer e candidatos ICE) ocorrerá dentro do próprio WebSocket mTLS já autenticado.

## 2. Dependências
- `TASK-01`, `TASK-03`, `TASK-07`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/webrtc/streamer.go`
- `linux-daemon/internal/webrtc/signaling_handler.go`
- `linux-daemon/internal/webrtc/webrtc_test.go`
- `linux-daemon/cmd/mock-client/main.go` (adicionar subcomando `stream-test`)

## 4. Requisitos Técnicos
1. **Gerenciador de PeerConnection (`streamer.go`):**
   - Ao receber `WebRtcSignal{type: START_STREAM}` do cliente Android:
     - Criar uma `webrtc.PeerConnection` com `TrackLocalStaticSample` para vídeo (`webrtc.MimeTypeH264`) e áudio (`webrtc.MimeTypeOpus`).
     - Iniciar o `MediaSource` (`TASK-07`) e bombear os samples de vídeo e áudio para as respectivas tracks.
     - Gerar um `SDP_OFFER` e enviá-lo ao cliente pelo WebSocket dentro de um `Envelope{WebRtcSignal}`.
2. **Negociação SDP e ICE (`signaling_handler.go`):**
   - Processar `SDP_ANSWER` recebido do cliente chamando `SetRemoteDescription`.
   - Trocar candidatos ICE (`OnICECandidate` <-> `AddICECandidate`) para estabelecer conexão direta na LAN, USB Tethering ou Tailscale.
   - Monitorar `OnConnectionStateChange`: caso mude para `Disconnected`, `Failed` ou `Closed`, desligar imediatamente o pipeline de captura da `TASK-07`.

## 5. Restrições
- Na rede local/USB/Tailscale, configure o ICE para funcionar mesmo sem internet externa (host candidates locais habilitados sem exigir STUN externo obrigatório).

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/webrtc/...`.
2. O teste automatizado deve criar uma `PeerConnection` do Daemon e uma `PeerConnection` de cliente em memória, realizar a troca de SDP Offer/Answer e ICE, transmitir 10 frames H.264 do modo de teste e confirmar a recepção dos pacotes RTP na ponta cliente.

---

