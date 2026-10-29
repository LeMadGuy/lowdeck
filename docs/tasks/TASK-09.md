# Task ID: TASK-09 — App Android: Setup do Projeto, Descoberta mDNS, Scanner QR e Cliente mTLS

## 1. Contexto Arquitetural
Iniciamos a construção do cliente Android nativo em **Kotlin + Jetpack Compose** dentro de `android-client/`. Esta task constrói a fundação do aplicativo: geração das classes Protobuf no Gradle, descoberta automática do PC via `NsdManager`, leitura do QR Code de pareamento e serviço persistente de conexão mTLS 1.3 + WebSocket.

## 2. Dependências
- `TASK-01`, `TASK-02`, `TASK-03`.

## 3. Escopo Exclusivo e Arquivos-Alvo
- `android-client/settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`
- `android-client/app/src/main/AndroidManifest.xml`
- `android-client/app/src/main/java/com/linuxdeck/client/network/MdnsDiscoveryManager.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/security/MtlsKeyManager.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/network/DeckConnectionService.kt`
- `android-client/app/src/main/java/com/linuxdeck/client/ui/pairing/PairingScreen.kt`

## 4. Requisitos Técnicos
1. **Configuração Gradle + Protobuf:**
   - Configurar o plugin `com.google.protobuf` no `app/build.gradle.kts` apontando o `sourceSets` diretamente para `../../proto/deck/v1` (garantindo fonte única de verdade com o daemon Go).
   - Incluir dependências: Jetpack Compose (Material 3), OkHttp (WebSocket + TLS), BouncyCastle/KeyStore (geração de certificado cliente X.509), CameraX + MLKit Barcode Scanning (ou ZXing) e Coroutines/Flow.
2. **Segurança mTLS (`MtlsKeyManager.kt`):**
   - Gerar par de chaves ECDSA do cliente Android e certificado X.509 armazenado de forma segura.
   - Configurar `OkHttpClient` com `SSLSocketFactory` customizado que apresenta o certificado do cliente e valida o fingerprint SHA-256 do certificado do servidor Linux obtido no QR Code (*Certificate Pinning*).
3. **Descoberta e Conexão (`MdnsDiscoveryManager.kt` & `DeckConnectionService.kt`):**
   - Descobrir serviços `_linuxdeck._tcp.` na rede via `NsdManager` e também permitir conexão manual/USB (`127.0.0.1:8443` para `adb reverse`).
   - Manter um `ForegroundService` (`DeckConnectionService`) com reconexão automática exponencial, expondo `StateFlow<ConnectionState>` e `SharedFlow<Envelope>` para todas as telas do app.
4. **Tela de Pareamento (`PairingScreen.kt`):**
   - Listar PCs descobertos na rede, abrir scanner de QR Code (ou permitir colar o JSON/Token de pareamento) e executar o handshake inicial.

## 5. Restrições
- Não bloqueie a Main Thread do Android com operações de rede ou criptografia; utilize `Dispatchers.IO`.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. O projeto deve estar estruturado corretamente com todos os imports e tipos Protobuf correspondentes ao `deck.proto`.
2. Se o SDK Android e Gradle estiverem disponíveis no ambiente, `./gradlew assembleDebug` e `./gradlew testDebugUnitTest` devem executar sem erros.

---

