#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="${ROOT_DIR}/proto/deck/v1"
PROTO_FILE="${PROTO_DIR}/deck.proto"
SCHEMA_FILE="${PROTO_DIR}/profile_schema.json"
DAEMON_DIR="${ROOT_DIR}/linux-daemon"
GO_OUT_DIR="${DAEMON_DIR}/pkg/protocol/v1"

if command -v go >/dev/null 2>&1; then
  GOBIN_DIR="$(go env GOBIN)"
  GOPATH_BIN="$(go env GOPATH)/bin"
  export PATH="${GOBIN_DIR:+${GOBIN_DIR}:}${GOPATH_BIN}:${PATH}"
fi

echo "==> Validating JSON Schema (${SCHEMA_FILE})..."
if [[ ! -f "${SCHEMA_FILE}" ]]; then
  echo "ERROR: Schema file not found at ${SCHEMA_FILE}" >&2
  exit 1
fi

if command -v jq >/dev/null 2>&1; then
  jq -e '
    .type == "object" and
    (.required | index("profile_id") != null) and
    (.required | index("rows") != null) and
    (.required | index("cols") != null) and
    (.required | index("pages") != null) and
    (."$defs".buttonType.enum | contains(["COMMAND", "KEY_COMBO", "MEDIA", "TELEMETRY_WIDGET", "FOLDER"]))
  ' "${SCHEMA_FILE}" >/dev/null
elif command -v python3 >/dev/null 2>&1; then
  python3 -m json.tool "${SCHEMA_FILE}" >/dev/null
else
  echo "ERROR: Neither jq nor python3 found to validate JSON schema." >&2
  exit 1
fi
echo "    JSON Schema is valid."

echo "==> Checking Protocol Buffers toolchain..."
if ! command -v protoc >/dev/null 2>&1; then
  echo "ERROR: 'protoc' is not installed or not in PATH." >&2
  exit 1
fi

if ! command -v protoc-gen-go >/dev/null 2>&1; then
  echo "ERROR: 'protoc-gen-go' is not installed or not in PATH." >&2
  echo "Run: go install google.golang.org/protobuf/cmd/protoc-gen-go@latest" >&2
  exit 1
fi

mkdir -p "${GO_OUT_DIR}"

echo "==> Compiling ${PROTO_FILE} -> ${GO_OUT_DIR}..."
protoc \
  --proto_path="${PROTO_DIR}" \
  --go_out="${GO_OUT_DIR}" \
  --go_opt=paths=source_relative \
  "deck.proto"

echo "==> Successfully generated Go protocol bindings in ${GO_OUT_DIR}."

