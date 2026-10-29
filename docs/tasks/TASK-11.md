# Task ID: TASK-11 — App Android: Trackpad Virtual Multi-gesto e Teclado com Modificadores

## 1. Contexto Arquitetural
Quando o usuário está no sofá ou longe da mesa, ele precisa controlar o cursor e digitar no Linux com a mesma precisão de um touchpad de notebook, incluindo teclas especiais do Linux (`Super/Meta`, `Ctrl`, `Alt`, `Esc`, `Tab`, `F1-F12`).

## 2. Dependências
- `TASK-04`, `TASK-09`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/ui/trackpad/TrackpadScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/trackpad/GestureDetectorSurface.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/trackpad/ModifierKeysBar.kt`

## 4. Requisitos Técnicos
1. **Superfície Multi-gesto de Baixa Latência (`GestureDetectorSurface.kt`):**
   - Capturar eventos brutos de `pointerInput` no Compose e traduzi-los em mensagens `Envelope`:
     - **Arrastar com 1 dedo:** envia `MouseMoveEvent` relativo (`dx`, `dy`) aplicando curva de aceleração suave configurável.
     - **Toque simples (1 dedo):** envia `MouseButtonEvent(LEFT, CLICK)`.
     - **Toque com 2 dedos:** envia `MouseButtonEvent(RIGHT, CLICK)`.
     - **Toque com 3 dedos:** envia `MouseButtonEvent(MIDDLE, CLICK)`.
     - **Arrastar com 2 dedos (Vertical/Horizontal):** envia `MouseScrollEvent`.
     - **Duplo toque + segurar (Drag & Drop):** envia `MouseButtonEvent(LEFT, PRESS)` no início e `RELEASE` ao soltar o dedo.
2. **Barra de Modificadores "Sticky" e Teclado (`ModifierKeysBar.kt`):**
   - Criar uma barra superior/inferior com teclas de estado (`Ctrl`, `Alt`, `Super`, `Shift`) que podem ser "travadas" (toggle) com um toque para combinar com cliques de mouse ou com o teclado virtual do Android, além de botões diretos para `Esc`, `Tab`, `Enter`, `Setas` e atalhos de workspace (`Super+1..5`).

## 5. Restrições
- Agrupe/limite (coalesce) eventos de movimento do touch para a taxa de atualização da tela (60Hz/120Hz) sem saturar a fila de envio do WebSocket.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Compilação limpa e testes unitários da função de cálculo de delta/aceleração do trackpad e máquina de estados dos gestos de 1 e 2 dedos.

---

