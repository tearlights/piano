#!/usr/bin/env bash
set -euo pipefail

# Enable local automation for a future Codex CLI session, including ADB access.
# This script changes the current user's global Codex configuration; it does not
# affect a currently running session or a managed desktop-app permission profile.

config_dir="${CODEX_CONFIG_DIR:-${HOME}/.codex}"
config_file="${config_dir}/config.toml"
backup_file="${config_file}.before-adb-network.$(date +%Y%m%d-%H%M%S).bak"

mkdir -p "${config_dir}"
touch "${config_file}"

if grep -Eq '^[[:space:]]*(sandbox_mode|approval_policy)[[:space:]]*=' "${config_file}" \
  || grep -Eq '^\[sandbox_workspace_write\]' "${config_file}"; then
  echo "Refusing to overwrite existing sandbox settings in: ${config_file}" >&2
  echo "Edit them manually, or remove the existing sandbox settings before rerunning this script." >&2
  exit 1
fi

cp --preserve=mode,timestamps "${config_file}" "${backup_file}"

cat >> "${config_file}" <<'TOML'

# Added by scripts/enable-codex-adb-network.sh.
# Keep filesystem writes limited to the workspace, but allow command networking.
sandbox_mode = "workspace-write"
approval_policy = "never"

[sandbox_workspace_write]
network_access = true
TOML

echo "Updated: ${config_file}"
echo "Backup:  ${backup_file}"
echo

echo "Completely exit and restart Codex before retrying ADB."
echo "If the desktop app still shows a managed/restricted profile, select Full access"
echo "or a profile that permits local network access; that managed policy overrides this file."
