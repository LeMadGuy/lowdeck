#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULE_SRC="${ROOT_DIR}/scripts/udev/99-deck-uinput.rules"
RULE_DST="/etc/udev/rules.d/99-deck-uinput.rules"
TARGET_USER="${SUDO_USER:-${USER:-$(id -un)}}"

echo "================================================================"
echo "  LinuxDeck — /dev/uinput Permissions Setup"
echo "================================================================"
echo "This script configures non-root access to /dev/uinput for Wayland,"
echo "X11, login screen (SDDM/GDM), and TTY virtual input injection:"
echo "  1. Loads the 'uinput' kernel module"
echo "  2. Installs ${RULE_DST} (MODE=0660, GROUP=input)"
echo "  3. Adds user '${TARGET_USER}' to the 'input' group"
echo "  4. Reloads and triggers udev rules"
echo "================================================================"

if [[ ! -f "${RULE_SRC}" ]]; then
  echo "ERROR: udev rule file not found at ${RULE_SRC}" >&2
  exit 1
fi

SUDO_CMD=""
if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    SUDO_CMD="sudo"
  else
    echo "ERROR: Please run this script as root or with sudo." >&2
    exit 1
  fi
fi

echo "==> Ensuring 'input' group exists..."
${SUDO_CMD} groupadd -f input

echo "==> Loading 'uinput' kernel module..."
${SUDO_CMD} modprobe uinput || true
echo "uinput" | ${SUDO_CMD} tee /etc/modules-load.d/deck-uinput.conf >/dev/null

echo "==> Installing udev rule to ${RULE_DST}..."
${SUDO_CMD} install -m 0644 "${RULE_SRC}" "${RULE_DST}"

echo "==> Adding user '${TARGET_USER}' to group 'input'..."
${SUDO_CMD} usermod -aG input "${TARGET_USER}"

echo "==> Reloading udev rules..."
${SUDO_CMD} udevadm control --reload-rules
${SUDO_CMD} udevadm trigger --subsystem-match=misc --attr-match=name=uinput || ${SUDO_CMD} udevadm trigger

echo "Done! Log out and log back in (or run 'newgrp input') for group membership to take effect."

