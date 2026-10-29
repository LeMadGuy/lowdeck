# Task ID: TASK-04 — Subsistema `/dev/uinput` (Mouse Relativo/Absoluto e Teclado Virtual)

## 1. Contexto Arquitetural
No Linux moderno com Wayland (Hyprland, KDE Plasma, GNOME, Sway), ferramentas antigas como `xdotool` não funcionam fora de janelas XWayland. Para ter controle total (incluindo tela de login e TTY), o daemon criará dispositivos virtuais em nível de kernel através de `/dev/uinput`.

## 2. Dependências
- `TASK-01`, `TASK-03`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/input/uinput_linux.go`
- `linux-daemon/internal/input/keymap.go`
- `linux-daemon/internal/input/handler.go`
- `linux-daemon/internal/input/input_test.go`
- `scripts/udev/99-deck-uinput.rules`
- `scripts/setup-permissions.sh`
- `linux-daemon/cmd/mock-client/main.go` (adicionar subcomando `input`)

## 4. Requisitos Técnicos
1. **Dispositivos Virtuais (`uinput_linux.go`):**
   - Criar uma interface `InputInjector` (com implementação real via `/dev/uinput` ou `github.com/bendahl/uinput` e uma implementação `Noop/Mock` para testes sem permissão de dispositivo).
   - Inicializar 3 dispositivos virtuais ao subir o daemon:
     1. `LinuxDeck Virtual Mouse (Relative)`: eventos `EV_REL` (`REL_X`, `REL_Y`, `REL_WHEEL`, `REL_HWHEEL`) e botões (`BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`, `BTN_SIDE`, `BTN_EXTRA`).
     2. `LinuxDeck Virtual TouchScreen (Absolute)`: eventos `EV_ABS` (`ABS_X` e `ABS_Y` com range `0..65535`) para mapear toques na tela remota (`0.0..1.0` -> `0..65535`) com precisão instantânea.
     3. `LinuxDeck Virtual Keyboard`: todos os keycodes Linux padrão (`KEY_A`..`KEY_Z`, `KEY_F1`..`KEY_F12`, `KEY_LEFTMETA`, `KEY_LEFTCTRL`, `KEY_LEFTALT`, `KEY_LEFTSHIFT`, multimídia, etc.).
2. **Mapeador de Atalhos e Texto (`keymap.go`):**
   - Converter strings legíveis (ex: `"SUPER+SHIFT+Q"`, `"CTRL+ALT+T"`, `"VOLUMEDOWN"`) em sequências de eventos `KeyDown` dos modificadores -> `KeyPress` da tecla principal -> `KeyUp` dos modificadores na ordem inversa.
3. **Permissões `udev`:**
   - Criar `scripts/udev/99-deck-uinput.rules` com:
     `KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"`
   - Criar `scripts/setup-permissions.sh` que explica/instala a regra `udev` e adiciona o usuário atual ao grupo `input`.
4. **Integração no Roteador e CLI:**
   - Registrar os handlers de `MouseMoveEvent`, `MouseButtonEvent`, `MouseScrollEvent`, `KeyEvent` e `KeyComboEvent` no `router.go`.
   - Adicionar ao `mock-client`: `mock-client input move --dx 100 --dy 50`, `mock-client input abs --x 0.5 --y 0.5` e `mock-client input combo "ctrl+alt+t"`.

## 5. Restrições
- Se `/dev/uinput` não estiver acessível no ambiente de execução por falta de permissão, o daemon deve logar um aviso claro instruindo a rodar `scripts/setup-permissions.sh` e iniciar em modo degradado (sem dar crash no processo inteiro).
- Feche os descritores de arquivo `uinput` no `Close()` do daemon para destruir os dispositivos virtuais.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/input/...` validando o parser de combos (`"CTRL+SHIFT+ESC"`, `"SUPER+1"`, etc.) e a conversão de coordenadas normalizadas (`0.0..1.0` -> `0..65535`).
2. Compilar `daemon` e `mock-client` sem erros.

---

