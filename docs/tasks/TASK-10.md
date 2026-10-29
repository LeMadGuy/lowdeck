# Task ID: TASK-10 — App Android: UI do Macro Deck Dinâmico e Widgets de Telemetria ao Vivo

## 1. Contexto Arquitetural
Esta é a tela principal de uso diário como "Stream Deck" ao lado do PC. Ela renderiza um grid dinâmico de botões altamente responsivos, sincronizados com o perfil JSON do Linux (`TASK-05`), além de widgets visuais de CPU, RAM, Temperatura e Controle de Mídia (`TASK-06`).

## 2. Dependências
- `TASK-05`, `TASK-06`, `TASK-09`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/DeckScreen.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/DeckViewModel.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/components/DeckButtonCard.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/components/TelemetryWidgetCard.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/deck/components/MediaControllerBar.kt`

## 4. Requisitos Técnicos
1. **Grid Configurável (`DeckScreen.kt`):**
   - Renderizar o grid de acordo com `rows` x `cols` do perfil ativo (suportando modo retrato e modo paisagem com `KeepScreenOn` ativado).
   - Suportar múltiplas páginas (deslizar horizontalmente com `HorizontalPager`) e pastas de botões.
2. **Botões com Feedback Tátil e Estado em Tempo Real (`DeckButtonCard.kt`):**
   - Ao tocar em um botão, acionar `HapticFeedbackType.LongPress` (vibração curta e firme), enviar `MacroExecRequest` ou `KeyComboEvent` e mostrar indicador visual de execução (`RUNNING`, `SUCCESS` verde ou `ERROR` vermelho caso `exit_code != 0`).
   - Permitir edição rápida do botão mantendo-o pressionado (alterar título, ícone, cor e comando/atalho e sincronizar o perfil atualizado de volta para o daemon Linux).
3. **Widgets de Telemetria e Mídia (`TelemetryWidgetCard.kt` & `MediaControllerBar.kt`):**
   - Consumir o fluxo `TelemetrySnapshot` no `DeckViewModel` e exibir medidores circulares/barras animadas de CPU (%), RAM (GB), Temperatura (°C) e tráfego de rede.
   - Exibir barra compacta de mídia MPRIS2 com título da faixa atual, artista e botões `Prev`, `Play/Pause`, `Next` e `Volume`.

## 5. Restrições
- Garanta recomposição isolada no Jetpack Compose: a chegada de um pacote `TelemetrySnapshot` a cada 1 segundo deve recompor **apenas** os cards de telemetria, sem redesenhar os botões estáticos do grid.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Verificar compilação limpa do módulo de UI e testes unitários do `DeckViewModel` validando a atualização de estado de botões após receber `MacroStateSync` e `TelemetrySnapshot`.

---

