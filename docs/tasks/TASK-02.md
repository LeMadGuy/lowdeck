# Task ID: TASK-02 — Autenticação mTLS 1.3, Certificados X.509 e Pareamento via QR Code

## 1. Contexto Arquitetural
Como o daemon no Linux fornece acesso a um terminal interativo (`PTY`), execução de scripts e controle de mouse/teclado em nível de kernel, o canal de rede exige **Mutual TLS (mTLS 1.3)**. O pareamento inicial ocorre exibindo um QR Code no terminal do Linux (contendo IP/Hostname, Porta, Fingerprint SHA-256 do certificado do servidor e um Token de Pareamento de uso único válido por 5 minutos).

## 2. Dependências
- `TASK-01` (`pkg/protocol/v1`).

## 3. Escopo Exclusivo e Arquivos-Alvo
- `linux-daemon/internal/auth/ca.go`
- `linux-daemon/internal/auth/pairing.go`
- `linux-daemon/internal/auth/store.go`
- `linux-daemon/internal/auth/auth_test.go`

## 4. Requisitos Técnicos
1. **Gerenciamento de Certificados (`ca.go`):**
   - Na primeira execução, gerar um par de chaves ECDSA P-256 (ou Ed25519) e um certificado X.509 autoassinado de longa duração para o Daemon, salvando em `~/.config/deck-daemon/certs/server.{crt,key}` com permissão `0600`.
   - Configurar `tls.Config` exigindo `MinVersion: tls.VersionTLS13`.
2. **Armazenamento de Clientes Confiáveis (`store.go`):**
   - Persistir em `~/.config/deck-daemon/trusted_clients.json` a lista de dispositivos Android autorizados (`device_id`, `device_name`, `cert_fingerprint_sha256`, `paired_at`).
   - Implementar o callback `VerifyPeerCertificate` no `tls.Config` do servidor para validar se o certificado apresentado pelo cliente Android consta na lista de confiáveis.
3. **Fluxo de Pareamento e QR Code (`pairing.go`):**
   - Gerar um payload JSON de pareamento: `{"host": "...", "port": 8443, "pairing_port": 8444, "server_fp": "<sha256>", "token": "<random-256bit-hex>", "expires_at": 123456789}`.
   - Renderizar esse JSON como um QR Code em caracteres ASCII/UTF-8 diretamente no stdout do terminal (usando `github.com/mdp/qrterminal/v3` ou implementação equivalente).
   - Expor um endpoint temporário de pareamento que recebe o CSR/Certificado público do cliente junto com o `token` válido, registra o fingerprint no `store.go` e invalida o token imediatamente.

## 5. Restrições
- Não armazene chaves privadas sem permissão restrita (`0600`).
- Tokens de pareamento devem expirar em 5 minutos e suportar apenas 1 tentativa bem-sucedida.
- Não modifique outros pacotes além de `internal/auth`.

## 6. Critérios de Aceite (Auto-Validação pelo Agente)
1. Executar `cd linux-daemon && go test -v ./internal/auth/...`.
2. Os testes unitários devem cobrir: geração de certificados do servidor e do cliente em diretório temporário (`t.TempDir()`), validação de pareamento com token correto, rejeição de token expirado/inválido e rejeição de conexão TLS de um cliente com certificado não registrado no `store`.

---

