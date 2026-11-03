#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAEMON_DIR="${ROOT_DIR}/linux-daemon"
INSTALL_DIR="${HOME}/.local/bin"

mkdir -p "${INSTALL_DIR}"

echo "==> Building deck-daemon from ${DAEMON_DIR}..."
go -C "${DAEMON_DIR}" build -o "${INSTALL_DIR}/deck-daemon" ./cmd/daemon

echo "==> Building deck-mock-client from ${DAEMON_DIR}..."
go -C "${DAEMON_DIR}" build -o "${INSTALL_DIR}/deck-mock-client" ./cmd/mock-client

chmod +x "${INSTALL_DIR}/deck-daemon" "${INSTALL_DIR}/deck-mock-client"

echo "==> Installed successfully:"
echo "    ${INSTALL_DIR}/deck-daemon"
echo "    ${INSTALL_DIR}/deck-mock-client"

