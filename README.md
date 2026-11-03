# Linux Deck & Remote Control

Plataforma completa de **Macro Deck Dinâmico**, **Trackpad/Teclado Kernel-level (`/dev/uinput`)**, **Desktop Remoto de Baixíssima Latência (PipeWire + GStreamer + WebRTC)** e **Terminal Interativo (`PTY`)** entre um cliente **Android** e um host **Linux** (compatível com **Wayland**, **X11**, telas de login **SDDM/GDM** e **TTY**).

---

## Sumário

1. [Visão Geral e Arquitetura](#1-visão-geral-e-arquitetura)
2. [Estrutura do Monorepo](#2-estrutura-do-monorepo)
3. [Instalação de Dependências no Linux](#3-instalação-de-dependências-no-linux)
4. [Configuração de Permissões udev (`/dev/uinput`)](#4-configuração-de-permissões-udev-devuinput)
5. [Compilação do Protocolo e do Daemon Linux](#5-compilação-do-protocolo-e-do-daemon-linux)
6. [Execução do Daemon e Serviço `systemd --user`](#6-execução-do-daemon-e-serviço-systemd---user)
7. [Pareamento Seguro mTLS 1.3 via QR Code](#7-pareamento-seguro-mtls-13-via-qr-code)
8. [Conexão via Wi-Fi, Tailscale e Cabo USB (`adb reverse`)](#8-conexão-via-wi-fi-tailscale-e-cabo-usb-adb-reverse)
9. [Build e Instalação do Aplicativo Android](#9-build-e-instalação-do-aplicativo-android)
10. [Validação End-to-End via CLI (`mock-client`)](#10-validação-end-to-end-via-cli-mock-client)
11. [Solução de Problemas (Troubleshooting)](#11-solução-de-problemas-troubleshooting)

---

## 1. Visão Geral e Arquitetura

O sistema unifica quatro modos operacionais sobre um único canal de controle autenticado via **mTLS 1.3 (X.509 ECDSA P-256)** com **Certificate Pinning SHA-256** e **WebSockets Binários (`Protocol Buffers v3`)**:

| Aba no App | Modo Operacional | Tecnologia no Host Linux | Tecnologia no App Android |
| :--- | :--- | :--- | :--- |
| **Deck** | Grid dinâmico de macros, pastas, controle de mídia/energia e telemetria ao vivo | Engine assíncrona JSON (`~/.config/deck-daemon/profiles/`), `/proc`, `/sys`, D-Bus (`MPRIS2` + `systemd-logind`) | Jetpack Compose com recomposição isolada de `TelemetrySnapshot` (1 Hz), feedback háptico e edição rápida por *long-press* |
| **Trackpad** | Mouse multi-gesto com curva balística, scroll de 2 dedos e barra de modificadores *sticky* | Três dispositivos virtuais reais no Kernel via `/dev/uinput`: Mouse Relativo, Touch Absoluto (`0..65535`) e Teclado EVDEV completo | Superfície `awaitEachGesture` de 60–120 Hz + `ModifierKeysBar` (`Ctrl`, `Alt`, `Super`, `Shift`, `Esc`, `Tab`, `F1-F12`) |
| **Tela Remota** | Streaming de vídeo H.264 *zero-latency* + áudio Opus do sistema com toque direto ou cursor relativo | `xdg-desktop-portal` ScreenCast + PipeWire + GStreamer (`vaapih264enc` / `nvh264enc` / `x264enc`) + `pion/webrtc/v4` | `SurfaceViewRenderer` acelerado por hardware (`EglBase`), pinch-to-zoom e `TouchCoordinateMapper` com compensação *letterbox* |
| **Terminal** | Emulador de terminal Linux completo para emergências (mesmo sem Wayland/X11 ativo) | Sessões `creack/pty` reais (`$SHELL`) com redimensionamento `TIOCSWINSZ` em tempo real | Renderizador nativo Compose `Canvas` 100% offline (VT100/ANSI, 256 cores, TrueColor, `?1049h`) + barra rápida de Sysadmin |

---

## 2. Estrutura do Monorepo

```text
.
├── proto/deck/v1/
│   ├── deck.proto                  # Fonte única da verdade do protocolo binário (Envelope + Payloads)
│   └── profile_schema.json         # JSON Schema dos perfis de botões e widgets do Deck
├── scripts/
│   ├── gen-proto.sh                # Valida JSON Schema e gera bindings Go a partir do deck.proto
│   ├── setup-permissions.sh        # Configura módulo uinput, regra udev 0660 e grupo 'input'
│   └── udev/99-deck-uinput.rules   # Regra udev para acesso sem root ao /dev/uinput
├── linux-daemon/
│   ├── cmd/daemon/                 # Servidor principal Linux (mTLS, mDNS, uinput, PTY, Telemetria, WebRTC)
│   ├── cmd/mock-client/            # Cliente CLI completo para testes e automação de todos os subsistemas
│   ├── internal/                   # Módulos internos (auth, discovery, server, input, macro, pty, telemetry, dbus, capture, webrtc)
│   └── pkg/protocol/v1/            # Código Go gerado automaticamente pelo protoc
└── android-client/
    └── app/                        # Cliente Android (Kotlin + Jetpack Compose + OkHttp mTLS + WebRTC)
```

---

## 3. Instalação de Dependências no Linux

### Arch Linux / Manjaro
```bash
sudo pacman -S --needed \
  go protobuf jq \
  pipewire pipewire-pulse wireplumber xdg-desktop-portal \
  gstreamer gst-plugins-base gst-plugins-good gst-plugins-bad gst-plugins-ugly gst-libav gstreamer-vaapi
```

### Ubuntu 24.04+ / Debian 12+
```bash
sudo apt update && sudo apt install -y \
  golang-go protobuf-compiler jq \
  pipewire wireplumber xdg-desktop-portal \
  gstreamer1.0-tools gstreamer1.0-pipewire \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly \
  gstreamer1.0-libav gstreamer1.0-vaapi
```

### Fedora 40+
```bash
sudo dnf install -y \
  golang protobuf-compiler jq \
  pipewire wireplumber xdg-desktop-portal \
  gstreamer1-plugins-base gstreamer1-plugins-good \
  gstreamer1-plugins-bad-free gstreamer1-plugins-ugly-free \
  gstreamer1-vaapi
```

> **Plugin Go do Protobuf** (necessário apenas se você editar `proto/deck/v1/deck.proto`):
> ```bash
> go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
> ```

---

## 4. Configuração de Permissões `udev` (`/dev/uinput`)

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

## 5. Compilação do Protocolo e do Daemon Linux

### 5.1. Validar Schema e Gerar Protocol Buffers (Opcional)
```bash
./scripts/gen-proto.sh
```

### 5.2. Compilar e Instalar o `linux-daemon` (a partir da raiz do projeto)
```bash
# Opção recomendada: script automático (funciona de qualquer diretório)
./scripts/install-daemon.sh

# Ou manualmente a partir da raiz do repositório (usando flag -C do Go):
mkdir -p ~/.local/bin
go -C linux-daemon test -v -race ./...
go -C linux-daemon build -o ~/.local/bin/deck-daemon ./cmd/daemon
go -C linux-daemon build -o ~/.local/bin/deck-mock-client ./cmd/mock-client
```

Certifique-se de que `~/.local/bin` está no seu `PATH`.

---

## 6. Execução do Daemon e Serviço `systemd --user`

### Execução Direta em Terminal
```bash
deck-daemon
```

Flags suportadas pelo `deck-daemon`:
- `--config-dir <dir>`: Diretório de certificados, clientes confiáveis e perfis JSON (padrão: `~/.config/deck-daemon`).
- `--port <int>`: Porta principal do servidor WebSocket mTLS 1.3 (padrão: `8443`).
- `--pairing-port <int>`: Porta temporária do servidor TLS de pareamento (padrão: `8444`).
- `--host <ip/hostname>`: IP ou hostname anunciado no QR Code de pareamento (útil para Tailscale/VPN).
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

## 7. Pareamento Seguro mTLS 1.3 via QR Code

Como o daemon expõe shell `PTY` e dispositivos de input do Kernel, **nenhuma conexão sem certificado X.509 cliente pareado é aceita** na porta `8443`.

1. No host Linux, inicie o daemon em modo de pareamento (se o serviço `systemd` já estiver rodando na porta `8443`, pare-o momentaneamente com `systemctl --user stop linux-deck-daemon` ou use o pareamento na primeira execução):
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

## 8. Conexão via Wi-Fi, Tailscale e Cabo USB (`adb reverse`)

### 8.1. Rede Local Wi-Fi / Ethernet (mDNS Automático)
- O daemon anuncia automaticamente o serviço `_linuxdeck._tcp` via mDNS na LAN.
- Basta que o smartphone Android e o PC Linux estejam na mesma sub-rede local e que as portas TCP `8443` (controle mTLS) e `8444` (apenas durante `--pair`) estejam liberadas no firewall local:
  ```bash
  # Exemplo UFW (restringindo à rede local)
  sudo ufw allow from 192.168.0.0/16 to any port 8443,8444 proto tcp
  ```

### 8.2. Uso Remoto via Tailscale / WireGuard
- Para controlar seu Linux de outra rede com segurança ponto a ponto, informe o IP da interface Tailscale (`tailscale ip -4`) ao gerar o QR Code:
  ```bash
  deck-daemon --pair --host "$(tailscale ip -4)"
  ```
- O WebRTC (`pion/webrtc`) e o canal WebSocket mTLS operarão transparentemente sobre a interface `tailscale0`.

### 8.3. Uso via Cabo USB com Latência Sub-Milissegundo (`adb reverse`)
Ideal para redes Wi-Fi congestionadas, ambientes corporativos com isolamento de clientes AP ou máxima fluidez de vídeo WebRTC:

1. Conecte o smartphone Android ao PC Linux via cabo USB com **Depuração USB** ativada.
2. No PC Linux, crie o túnel reverso para as portas de controle e pareamento:
   ```bash
   adb reverse tcp:8443 tcp:8443
   adb reverse tcp:8444 tcp:8444
   ```
3. Se ainda não estiver pareado, gere o QR Code apontando para loopback:
   ```bash
   deck-daemon --pair --host 127.0.0.1
   ```
4. Na tela de pareamento do app Android, toque no atalho **USB (`127.0.0.1`)** — toda a sinalização mTLS 1.3, input `/dev/uinput`, PTY e tráfego local passarão direto pelo barramento USB 2.0/3.0.

---

## 9. Build e Instalação do Aplicativo Android

Pré-requisitos de build: **JDK 17+** e **Android SDK (API 35)**. O Gradle compila automaticamente `proto/deck/v1/deck.proto` em classes Kotlin/Java Lite.

```bash
cd android-client

# Executa todos os testes unitários JVM (mTLS, Deck, Curva de Aceleração Trackpad, Coordenadas WebRTC e Terminal ANSI)
./gradlew testDebugUnitTest

# Gera o APK de debug em app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Instala diretamente no dispositivo conectado via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 10. Validação End-to-End via CLI (`mock-client`)

O binário `deck-mock-client` (`linux-daemon/cmd/mock-client`) permite testar e automatizar todos os subsistemas do daemon sem precisar de um dispositivo Android:

```bash
# 1. Parear o cliente CLI usando o token impresso por `deck-daemon --pair`
deck-mock-client pair --token <TOKEN_HEX> --host 127.0.0.1:8444

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

# 8. Validar streaming WebRTC H.264/Opus (use DECK_CAPTURE_TEST_SOURCE=1 no daemon em ambientes headless)
deck-mock-client stream-test --frames 15 --width 1280 --height 720 --fps 60
```

---

## 11. Solução de Problemas (Troubleshooting)

* **O cursor não se move no Wayland:**
  Verifique se `ls -l /dev/uinput` pertence ao grupo `input` com permissão `crw-rw----` e se `id -nG` lista o grupo `input`. Reexecute `./scripts/setup-permissions.sh` e refaça login na sessão gráfica.
* **Tela preta ou pedido de permissão ao abrir "Tela Remota" no Wayland:**
  Compositores Wayland (GNOME, KDE Plasma, Hyprland, Sway) exigem confirmação inicial do portal `xdg-desktop-portal` (`ScreenCast`) na tela do PC para liberar o stream PipeWire. Para testes automatizados em servidores sem monitor físico, inicie o daemon com `DECK_CAPTURE_TEST_SOURCE=1 deck-daemon`.
* **Codificação de vídeo usando CPU em vez de GPU:**
  O pipeline GStreamer detecta automaticamente encoders de hardware nesta ordem de prioridade: `vah264lpenc` / `vaapih264enc` (Intel/AMD VA-API) → `nvh264enc` (NVIDIA NVENC) → `x264enc tune=zerolatency speed-preset=ultrafast` (fallback universal em software). Instale `gstreamer-vaapi` (ou drivers VA-API correspondentes) para habilitar aceleração por GPU.

