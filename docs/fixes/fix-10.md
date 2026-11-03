# FIX-01 — Canal UDP Fast-Lane e Fim dos Picos de Latência (430 ms)

## 1. Diagnóstico Técnico do Bug
Mesmo com a priorização de filas em memória na v2, o **LowDeck** continuou apresentando picos de latência de até $430\text{ ms}$ tanto via Wi-Fi quanto via cabo USB.
* **Causa Raiz:** Todos os pacotes (Ping, Trackpad, Terminal e Tela Remota) compartilhavam o mesmo socket TCP (`:8443`). Quando qualquer carga maior entrava no buffer de envio do kernel Linux (`SO_SNDBUF`) ou no daemon `adbd` (`adb reverse`), pacotes minúsculos de Ping e Trackpad sofriam **Head-of-Line (HoL) Blocking** aguardando retransmissões e *Delayed ACKs* do TCP.

## 2. Objetivo da Correção
Criar um canal **UDP Fast-Lane** dedicado na porta `:8443/udp` (operando em paralelo ao socket TCP de controle) com protocolo binário de tamanho fixo ($5$ a $9\text{ bytes}$), sem alocação dinâmica de memória (zero GC pressure) e com marcação de pacote `IPTOS_LOWDELAY` ($0\text{x}10$).

---

## 3. Escopo e Arquivos-Alvo

* **Daemon Linux (Go):**
  * `linux-daemon/internal/server/udp_fastlane.go` (Novo)
  * `linux-daemon/internal/server/udp_fastlane_test.go` (Novo)
  * `linux-daemon/cmd/daemon/main.go` (Iniciar o listener UDP junto ao servidor principal)
* **Cliente Android (Kotlin):**
  * `android-client/app/src/main/java/com/linuxdeck/client/network/UdpFastControlClient.kt` (Novo)
  * Integrar `UdpFastControlClient` no `TrackpadInputHandler.kt` e no medidor de Ping/Latência do app.

---

## 4. Especificação Técnica Passo a Passo

### 4.1. Protocolo Binário de Datagramas UDP (Big-Endian)
Cada datagrama UDP possui no máximo $16\text{ bytes}$, onde o byte `0` define o `OpCode`:

| OpCode | Nome | Tamanho Total | Layout dos Bytes | Ação no Daemon Linux |
| :---: | :--- | :---: | :--- | :--- |
| `0x01` | `OP_PING` | $9\text{ bytes}$ | `[0x01][int64 client_ts_nanos]` | Responde imediatamente no mesmo endereço UDP com `[0x02][int64 client_ts_nanos]`. |
| `0x02` | `OP_PONG` | $9\text{ bytes}$ | `[0x02][int64 client_ts_nanos]` | Recebido pelo Android: calcula $\text{RTT} = \frac{\text{System.nanoTime()} - \text{client\_ts\_nanos}}{10^6}\text{ ms}$. |
| `0x10` | `OP_MOUSE_REL` | $5\text{ bytes}$ | `[0x10][int16 dx][int16 dy]` | Injeta movimento relativo imediato no worker `/dev/uinput`. |
| `0x11` | `OP_MOUSE_ABS` | $5\text{ bytes}$ | `[0x11][uint16 norm_x][uint16 norm_y]` | Coordenadas mapeadas em $0..65535$; injeta posição absoluta no `/dev/uinput`. |
| `0x12` | `OP_MOUSE_BTN` | $3\text{ bytes}$ | `[0x12][uint8 button][uint8 action]` | `button`: `1=LEFT`, `2=RIGHT`, `3=MIDDLE`; `action`: `1=DOWN`, `0=UP`, `2=CLICK`. |
| `0x13` | `OP_MOUSE_SCROLL` | $5\text{ bytes}$ | `[0x13][int16 v_scroll][int16 h_scroll]` | Injeta rolagem vertical/horizontal no `/dev/uinput`. |

### 4.2. Implementação no Daemon Linux (`udp_fastlane.go`)
1. Abrir socket UDP em `0.0.0.0:8443` via `net.ListenUDP("udp", addr)`.
2. Configurar buffers de socket no kernel: `conn.SetReadBuffer(262144)` e `conn.SetWriteBuffer(262144)`.
3. No loop de leitura (`ReadFromUDP`), utilizar um array estático de $32\text{ bytes}$ pré-alocado fora do loop (`var buf [32]byte`) para garantir **zero alocações no heap por pacote**.
4. Se `buf[0] == 0x01` (`OP_PING`), alterar `buf[0] = 0x02` e chamar `conn.WriteToUDP(buf[:9], remoteAddr)` na mesma linha, antes de qualquer outra operação.
5. Para os demais OpCodes (`0x10` a `0x13`), decodificar os inteiros via `binary.BigEndian` e despachar de forma não-bloqueante para o `uinput_worker`.

### 4.3. Implementação no Cliente Android (`UdpFastControlClient.kt`)
1. Instanciar um `DatagramSocket()` configurado com:
   * `socket.trafficClass = 0x10` (`IPTOS_LOWDELAY` — instrui o kernel Android e roteadores Wi-Fi a colocarem o pacote na fila de voz/baixa latência WMM).
   * `socket.soTimeout = 1000`.
2. Manter um loop de medição de Ping a cada $1000\text{ ms}$ em `Dispatchers.IO` enviando `OP_PING` (`0x01`) e atualizando um `StateFlow<Long> rttMs`.
3. **Fallback Automático para `adb reverse`:**
   * Se o host configurado for `127.0.0.1` (túnel `adb reverse`, que só encaminha TCP) e o cliente não receber nenhum `OP_PONG` UDP após $2\text{ segundos}$, acionar um flag `useTcpFallback = true` mantendo o funcionamento normal pelo socket TCP (com `TCP_NODELAY` ativo).
   * Quando conectado via Wi-Fi ou **USB Tethering (RNDIS, ex: `192.168.x.x`)**, o UDP Fast-Lane estará 100% ativo.

---

## 5. Restrições e Guardrails
* Não aloque objetos `ByteBuffer` novos a cada evento de movimento no Android; reutilize um `ByteBuffer.allocate(16)` sincronizado ou array de bytes fixo.
* Remova o tráfego de terminal e de vídeo da porta `:8443` (conforme `FIX-02` e `FIX-03`) para que mesmo em modo `adb reverse` o socket `:8443` trafegue apenas pacotes leves de controle.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. `cd linux-daemon && go test -v -race ./internal/server/...` deve validar o envio de `OP_PING` e recebimento de `OP_PONG` em menos de $1\text{ ms}$ em loopback, além da decodificação exata de coordenadas negativas e positivas em `OP_MOUSE_REL` (`int16`).
2. `go build ./...` deve compilar o daemon sem warnings.