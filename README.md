# Linux Deck & Remote Control

Plataforma completa de **Macro Deck Dinâmico**, **Trackpad/Teclado Kernel-level (`/dev/uinput`)**, **Desktop Remoto de Baixíssima Latência (Stream Contínuo MJPEG)** e **Terminal Interativo (`PTY` + `xterm.js`)** entre um cliente **Android** e um host **Linux** (compatível com **Wayland**, **X11**, telas de login **SDDM/GDM** e **TTY**).

---

## Sumário

1. [Visão Geral e Arquitetura](#1-visão-geral-e-arquitetura)
2. [Instalação de Dependências no Linux](#2-instalação-de-dependências-no-linux)
3. [Configuração de Permissões udev (`/dev/uinput`)](#3-configuração-de-permissões-udev-devuinput)
4. [Compilação do Protocolo e do Daemon Linux](#4-compilação-do-protocolo-e-do-daemon-linux)
5. [Execução do Daemon e Serviço `systemd --user`](#5-execução-do-daemon-e-serviço-systemd---user)
6. [Pareamento Seguro mTLS 1.3 via QR Code](#6-pareamento-seguro-mtls-13-via-qr-code)
7. [Conexão Híbrida Automática: Wi-Fi, Cabo USB (`adb reverse`) e Tailscale](#7-conexão-híbrida-automática-wi-fi-cabo-usb-adb-reverse-e-tailscale)
8. [Build e Instalação do Aplicativo Android](#8-build-e-instalação-do-aplicativo-android)
9. [Validação End-to-End via CLI (`mock-client`)](#9-validação-end-to-end-via-cli-mock-client)
10. [Solução de Problemas (Troubleshooting)](#10-solução-de-problemas-troubleshooting)

---

## 1. Visão Geral e Arquitetura

O sistema opera sob arquitetura de canal duplo dedicada (Dual-Channel): canal de controle e sincronização autenticado via **mTLS 1.3 (X.509 ECDSA P-256)** com **Certificate Pinning SHA-256** e **WebSockets Binários (`Protocol Buffers v3`)**, canal de input ultra-rápido via **UDP Fast-Lane (`:8443/udp`)** e servidor dedicado de mídia/PTY (`:8444`):

| Aba no App | Modo Operacional | Tecnologia no Host Linux | Tecnologia no App Android |
| :--- | :--- | :--- | :--- |
| **Deck** | Grid dinâmico de macros, pastas, controle de mídia/energia e telemetria ao vivo | Engine assíncrona JSON (`~/.config/deck-daemon/profiles/`), `/proc`, `/sys`, D-Bus (`MPRIS2` + `systemd-logind`) | Jetpack Compose com recomposição isolada de `TelemetrySnapshot` (1 Hz), feedback háptico e edição rápida por *long-press* |
| **Trackpad** | Mouse multi-gesto com curva balística, scroll de 2 dedos e barra de modificadores *sticky* | Três dispositivos virtuais reais no Kernel via `/dev/uinput`: Mouse Relativo, Touch Absoluto (`0..65535`) e Teclado EVDEV completo + listener **UDP Fast-Lane (`:8443/udp`)** | Superfície `awaitEachGesture` de 60–120 Hz + canal UDP de baixa latência (< 1 ms USB / ~15 ms Wi-Fi) + `ModifierKeysBar` (`Ctrl`, `Alt`, `Super`, `Shift`, `Esc`, `Tab`, `F1-F12`) |
| **Tela Remota** | Streaming de vídeo contínuo com toque direto absoluto ou cursor relativo | Servidor MJPEG contínuo sob demanda (`:8444/screen.mjpg`) com processo persistente único via captura nativa (KDE KWin D-Bus ScreenShot2 / PipeWire / FFmpeg) | Leitor contínuo `multipart/x-mixed-replace` decodificado em `Bitmap.Config.RGB_565` no Jetpack Compose com zoom, pan e `TouchCoordinateMapper` com compensação *letterbox* |
| **Terminal** | Emulador de terminal Linux completo | Sessões `creack/pty` reais (`$SHELL`) com redimensionamento `TIOCSWINSZ` e WebSocket binário puro dedicado (`:8444/ws/pty`) | Interface `xterm.js` via WebView acelerada por hardware isolada da UI Thread (VT100/ANSI, cores TrueColor, suporte a mouse) + barra rápida de Sysadmin |

---

## 2. Instalação de Dependências no Linux

### Arch Linux / Manjaro
```bash
sudo pacman -S --needed \
  go protobuf jq android-tools \
  pipewire pipewire-pulse wireplumber xdg-desktop-portal \
  ffmpeg imagemagick
```

### Ubuntu 24.04+ / Debian 12+
```bash
sudo apt update && sudo apt install -y \
  golang-go protobuf-compiler jq adb \
  pipewire wireplumber xdg-desktop-portal \
  ffmpeg imagemagick
```

### Fedora 40+
```bash
sudo dnf install -y \
  golang protobuf-compiler jq android-tools \
  pipewire wireplumber xdg-desktop-portal \
  ffmpeg ImageMagick
```

> **Plugin Go do Protobuf** (necessário apenas se você editar `proto/deck/v1/deck.proto`):
> ```bash
> go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
> ```

---

## 3. Configuração de Permissões `udev` (`/dev/uinput`)

Para que o daemon injete eventos reais de mouse e teclado no Kernel Linux **sem rodar como `root`** (funcionando no Wayland, X11, SDDM/GDM e TTY), execute o script incluído no repositório:

```bash
./scripts/setup-permissions.sh
```

O script executa automaticamente:
1. Carregamento imediato e persistente do módulo `uinput` (`/etc/modules-load.d/deck-uinput.conf`);
2. Instalação de `/etc/udev/rules.d/99-deck-uinput.rules` (`KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"`);
3. Adição do seu usuário ao grupo `input` (`usermod -aG input $USER`);
4. Recarregamento das regras `udevadm`.

**Importante:** Encerre a sessão do Linux e faça login novamente (ou execute `newgrp input` no terminal atual) para ativar a associação ao grupo `input`. Verifique com:
```bash
ls -l /dev/uinput
# Saída esperada: crw-rw---- 1 root input 10, 223 ... /dev/uinput
```

---

## 4. Compilação do Protocolo e do Daemon Linux

### 4.1. Validar Schema e Gerar Protocol Buffers (Opcional)
```bash
./scripts/gen-proto.sh
```

### 4.2. Compilar e Instalar o `linux-daemon`
```bash
# Opção recomendada: compilação direta e instalação em ~/.local/bin/deck-daemon
cd linux-daemon
go test -v ./...
go build -o ~/.local/bin/deck-daemon ./cmd/daemon
go build -o ~/.local/bin/deck-mock-client ./cmd/mock-client
```

Certifique-se de que `~/.local/bin` está no seu `PATH`.

---

## 5. Execução do Daemon e Serviço `systemd --user`

### Execução Direta em Terminal
```bash
deck-daemon
```

Flags suportadas pelo `deck-daemon`:
- `--config-dir <dir>`: Diretório de certificados, clientes confiáveis e perfis JSON (padrão: `~/.config/deck-daemon`).
- `--port <int>`: Porta principal do servidor WebSocket mTLS 1.3 e UDP Fast-Lane (padrão: `8443`).
- `--screen-port <int>`: Porta do servidor de stream MJPEG de tela e terminal PTY `xterm.js` (padrão: `8444`).
- `--pairing-port <int>`: Porta temporária do servidor TLS de pareamento (padrão: `8445`).
- `--host <ip/hostname>`: IP ou hostname anunciado no QR Code de pareamento (detectado automaticamente via rota de saída ativa se omitido).
- `--pair`: Gera um token de uso único válido por **5 minutos**, exibe o **QR Code ANSI** no terminal e abre o endpoint temporário de pareamento.
- `--print-qr-only`: Quando combinado com `--pair`, imprime o QR Code/JSON e encerra imediatamente.

### Configuração como Serviço `systemd --user` (Inicialização Automática)

Crie o arquivo `~/.config/systemd/user/linux-deck-daemon.service`:

```ini
[Unit]
Description=Linux Deck & Remote Control Daemon
After=network-online.target pipewire.service xdg-desktop-portal.service
Wants=pipewire.service

[Service]
Type=simple
ExecStart=%h/.local/bin/deck-daemon
Restart=on-failure
RestartSec=3
Environment=XDG_RUNTIME_DIR=/run/user/%U

[Install]
WantedBy=default.target
```

Ative e inicie o serviço:
```bash
systemctl --user daemon-reload
systemctl --user enable --now linux-deck-daemon
journalctl --user -u linux-deck-daemon -f
```

---

## 6. Pareamento Seguro mTLS 1.3 via QR Code

Como o daemon expõe shell `PTY` e dispositivos de input do Kernel, **nenhuma conexão sem certificado X.509 cliente pareado é aceita** na porta `8443`.

1. No host Linux, inicie o daemon em modo de pareamento:
   ```bash
   deck-daemon --pair
   ```
2. Um **QR Code** (válido por 5 minutos e de uso único) será renderizado diretamente no terminal junto com o fingerprint SHA-256 do servidor.
3. No aplicativo Android:
   - Toque no ícone de **QR Code** no canto superior direito (ou na tela inicial de pareamento);
   - Toque em **Escanear QR Code** (ou cole o JSON/Token manualmente);
   - O app Android gerará seu par de chaves **ECDSA P-256** no Android KeyStore, enviará o certificado público X.509 para registro em `~/.config/deck-daemon/trusted_clients.json` (`0600`) e fixará (*pinning*) o hash SHA-256 do certificado do seu PC.
4. Após o pareamento, a sessão mTLS 1.3 reconecta automaticamente em segundo plano sempre que o app estiver aberto.

---

## 7. Conexão Híbrida Automática: Wi-Fi, Cabo USB (`adb reverse`) e Tailscale

O `deck-daemon` gerencia a infraestrutura de rede de forma totalmente autônoma:

```text
[Wi-Fi]        Host: 192.168.1.15 (Portas 8443, 8444, 8445 TCP / 8443 UDP)
[USB / ADB]    Portas mapeadas no celular -> 127.0.0.1 (Watcher automático ativo)
```

### 7.1. Conexão Híbrida Transparente (Basta conectar o cabo ou o Wi-Fi)
- **Watcher USB em Segundo Plano:** O daemon monitora a conexão ADB a cada 3 segundos. Assim que o smartphone for conectado via cabo USB, as portas `8443`, `8444` e `8445` são mapeadas automaticamente via `adb reverse`.
- **Compatibilidade Multi-Interface no Wi-Fi:** Se o PC estiver conectado ao cabo de rede (Ethernet) e ao Wi-Fi simultaneamente na mesma sub-rede, o daemon ajusta a métrica do NetworkManager para evitar descarte de pacotes pelo filtro reverso do Linux (`rp_filter`).
- **Alternância Automática no Android:** O app Android gerencia uma lista dinâmica de hosts candidatos (`IP Wi-Fi` $\leftrightarrow$ `127.0.0.1`). Se o Wi-Fi cair ou você conectar o cabo, a transição é imediata e sem interrupção.

### 7.2. Liberação de Firewall (UFW)
Se você utiliza firewall local no Linux (UFW), libere as portas com:
```bash
sudo ufw allow 8443,8444,8445/tcp && sudo ufw allow 8443/udp
```

### 7.3. Uso Remoto via Tailscale / WireGuard
Para controlar seu Linux a partir de redes externas com segurança ponto a ponto:
```bash
deck-daemon --pair --host "$(tailscale ip -4)"
```

---

## 8. Build e Instalação do Aplicativo Android

Pré-requisitos de build: **JDK 17+** e **Android SDK (API 35)**. O Gradle compila automaticamente `proto/deck/v1/deck.proto` em classes Kotlin/Java Lite.

```bash
cd android-client

# Executa todos os testes unitários JVM
./gradlew testDebugUnitTest

# Gera o APK de debug em app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Instala diretamente no dispositivo conectado via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 9. Validação End-to-End via CLI (`mock-client`)

O binário `deck-mock-client` (`linux-daemon/cmd/mock-client`) permite testar e automatizar todos os subsistemas do daemon sem precisar de um dispositivo Android:

```bash
# 1. Parear o cliente CLI usando o token impresso por `deck-daemon --pair`
deck-mock-client pair --token <TOKEN_HEX> --host 127.0.0.1:8445

# 2. Medir latência RTT mTLS 1.3 + Protobuf (Ping/Pong)
deck-mock-client ping --host 127.0.0.1:8443

# 3. Testar injeção de mouse relativo, toque absoluto (0.0..1.0) e atalhos de teclado (/dev/uinput)
deck-mock-client input move --dx 120 --dy -45
deck-mock-client input abs --x 0.5 --y 0.5
deck-mock-client input combo "CTRL+ALT+T"

# 4. Disparar botão de macro do perfil e acompanhar estados RUNNING -> SUCCESS/FAILED
deck-mock-client macro --button-id btn-htop --cmd "uname -a"

# 5. Abrir sessão interativa de Terminal PTY remoto
deck-mock-client pty --cols 120 --rows 40 --cmd "ls -la --color=always"

# 6. Receber snapshots de telemetria (/proc/stat, /proc/meminfo, temperatura, rede e mídia)
deck-mock-client telemetry --count 3

# 7. Controlar reprodução de mídia MPRIS2 e sessão de energia systemd-logind via D-Bus
deck-mock-client media --action play-pause
deck-mock-client power --action lock
```

---

## 10. Solução de Problemas (Troubleshooting)

* **O cursor não se move no Wayland:**
  Verifique se `ls -l /dev/uinput` pertence ao grupo `input` com permissão `crw-rw----` e se `id -nG` lista o grupo `input`. Reexecute `./scripts/setup-permissions.sh` e refaça login na sessão gráfica.
* **Celular não conecta via Wi-Fi em computadores com cabo de rede e Wi-Fi conectados:**
  Quando o PC possui placa Ethernet e Wi-Fi conectadas ao mesmo roteador, o Linux pode descartar os pacotes recebidos pela interface sem fio por proteção de caminho reverso (`rp_filter`). O daemon configura automaticamente a métrica de rotas do NetworkManager na inicialização para resolver essa assimetria.
* **Teclas ou botões de mouse presos no host:**
  O daemon inclui auto-release automático com watchdog de 5 segundos e rotina `ReleaseAll` em eventos de desconexão, garantindo que modificadores (`Ctrl`, `Alt`, `Shift`) e botões de mouse sejam sempre liberados no `/dev/uinput`.
* **Portas bloqueadas por firewall:**
  Certifique-se de que as portas `8443`, `8444` e `8445` (TCP) e `8443` (UDP) estão liberadas para a rede local (`sudo ufw allow 8443,8444,8445/tcp && sudo ufw allow 8443/udp`).
