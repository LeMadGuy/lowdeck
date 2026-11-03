#!/usr/bin/env bash
set -euo pipefail

echo "==> Verificando conexão ADB com o dispositivo Android..."
if ! command -v adb &>/dev/null; then
    echo "ERRO: comando 'adb' não encontrado no PATH."
    exit 1
fi

DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" || true)
if [ -z "$DEVICES" ]; then
    echo "Nenhum dispositivo Android detectado em modo 'device'."
    echo "Conecte o celular com cabo USB e ative a 'Depuração USB' nas Opções de Desenvolvedor."
    exit 1
fi

echo "==> Dispositivo Android detectado:"
echo "$DEVICES"

echo "==> Mapeando portas reversas via ADB..."
adb reverse tcp:8443 tcp:8443
adb reverse tcp:8444 tcp:8444
adb reverse tcp:8445 tcp:8445

echo ""
echo "==> Portas ativas no adb reverse:"
adb reverse --list

echo ""
echo "==> Sucesso! Para iniciar o pareamento via USB, execute no terminal:"
echo "    deck-daemon --pair --host 127.0.0.1"

