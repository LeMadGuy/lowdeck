# Task ID: TASK-13 — App Android: Emulador de Terminal Integrado (PTY) e Polimento Final

## 1. Contexto Arquitetural
Para garantir administração total do Linux mesmo se o servidor gráfico (Wayland/X11) travar ou estiver desligado, o aplicativo inclui uma aba de Terminal Interativo conectada diretamente ao stream `PTY` do daemon (`TASK-05`), além da navegação unificada entre os 4 modos do app (Deck, Trackpad, Desktop Remoto e Terminal).

## 2. Dependências
- `TASK-05`, `TASK-09`, `TASK-10`, `TASK-11`, `TASK-12`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/ui/terminal/TerminalScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/terminal/TerminalViewModel.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/navigation/MainScaffold.kt`
- `README.md` (Documentação completa de build, setup e uso do projeto)

## 4. Requisitos Técnicos
1. **Emulador de Terminal (`TerminalScreen.kt` & `TerminalViewModel.kt`):**
   - Ao abrir a tela, enviar `PtyOpenRequest` e sincronizar dimensões de colunas/linhas via `PtyResizeEvent`.
   - Renderizar sequências ANSI/VT100 e cores de 256 bits (via `xterm.js` empacotado localmente em `assets/` dentro de uma `WebView` de latência zero sem acesso externo à internet, ou canvas nativo de terminal).
   - Incluir barra de ferramentas de Sysadmin acima do teclado com teclas rápidas: `ESC`, `TAB`, `CTRL+C`, `CTRL+Z`, `CTRL+D`, `CTRL+L`, `Setas (↑ ↓ ← →)`, `|` (pipe), `/` e `-`.
2. **Navegação Unificada (`MainScaffold.kt`):**
   - Integrar na barra de navegação principal as 4 abas: **Deck**, **Trackpad**, **Tela Remota** e **Terminal**, mais o indicador de status da conexão mTLS/latência no topo.
3. **Documentação de Produção (`README.md`):**
   - Documentar o passo a passo completo: instalação de dependências no Linux, configuração das permissões `udev`, execução do daemon (ou serviço `systemd --user`), pareamento via QR Code, uso via Wi-Fi/Tailscale e uso via cabo USB (`adb reverse`).

## 5. Restrições
- Quaisquer assets do terminal (`xterm.js`) devem ser servidos 100% offline/localmente sem depender de CDN na internet.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Compilação completa tanto do `linux-daemon` (`go build ./...` e `go test ./...`) quanto do `android-client`.
2. Verificar que todas as rotas do `MainScaffold.kt` estão conectadas e que o `README.md` documenta todos os comandos reais criados ao longo das 13 tasks.
