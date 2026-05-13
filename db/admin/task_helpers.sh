#!/usr/bin/env bash

# Shared helpers for Taskfile wrappers and db taskfiles.

validate_profile_for_mode() {
  local mode="$1"
  local profile="$2"

  case "$mode" in
    backup|flyway|common)
      case "$profile" in
        local|dev|test|prod) ;;
        *)
          echo "ERROR: Invalid ENV_PROFILE='$profile'. Allowed: local|dev|test|prod"
          return 1
          ;;
      esac
      ;;

    restore)
      case "$profile" in
        local|dev|test) ;;
        prod)
          echo "ERROR: ENV_PROFILE=prod is not allowed for restore. Allowed: local|dev|test"
          return 1
          ;;
        *)
          echo "ERROR: Invalid ENV_PROFILE='$profile'. Allowed: local|dev|test"
          return 1
          ;;
      esac
      ;;

    *)
      echo "ERROR: Unsupported mode '$mode'"
      return 1
      ;;
  esac
}

load_profile_env() {
  local profile="$1"

  local common_file=".env.common"
  local profile_file=".env.${profile}"

  if [ ! -f "$common_file" ]; then
    echo "ERROR: Missing env file: $common_file"
    return 1
  fi

  if [ ! -f "$profile_file" ]; then
    echo "ERROR: Missing env file: $profile_file"
    return 1
  fi

  set -a
  . "./$common_file"
  . "./$profile_file"
  set +a
}

map_source_to_default_env() {
  export DB_HOST="${DB_SOURCE_RDS_HOST:-${DB_HOST:-}}"
  export DB_PORT="${DB_SOURCE_PORT:-${DB_PORT:-5432}}"
  export LOCAL_DB_PORT="${DB_SOURCE_LOCAL_PORT:-${LOCAL_DB_PORT:-54321}}"
  export DB_NAME="${DB_SOURCE_NAME:-${DB_NAME:-}}"
  export DB_USER="${DB_SOURCE_USER:-${DB_USER:-}}"
  export DB_PASSWORD="${DB_SOURCE_PASSWORD:-${DB_PASSWORD:-}}"
  export SSM_TARGET="${DB_SOURCE_SSM_TARGET:-${SSM_TARGET:-}}"
}

load_and_map_default_env() {
  local profile="$1"
  validate_profile_for_mode common "$profile"
  load_profile_env "$profile"
  map_source_to_default_env
}

map_source_to_flyway_env() {
  export FLYWAY_DB_NAME="${DB_SOURCE_NAME:?DB_SOURCE_NAME missing}"
  export FLYWAY_USER="${DB_SOURCE_USER:?DB_SOURCE_USER missing}"
  export FLYWAY_PASSWORD="${DB_SOURCE_PASSWORD:-}"
  export LOCAL_DB_PORT="${DB_SOURCE_LOCAL_PORT:?DB_SOURCE_LOCAL_PORT missing}"
  export DB_HOST="${DB_SOURCE_RDS_HOST:?DB_SOURCE_RDS_HOST missing}"
  export DB_PORT="${DB_SOURCE_PORT:-5432}"
  export SSM_TARGET="${DB_SOURCE_SSM_TARGET:-}"
}

start_ssm_tunnel() {
  local session="$1"
  local label="$2"
  local access="$3"
  local host="$4"
  local local_port="$5"
  local remote_port="$6"
  local ssm_target="$7"
  local aws_region="$8"
  local aws_profile="$9"
  local forbid_prod_host="${10:-false}"

  if [ "$access" != "ssm" ]; then
    echo "${label} access=$access -> tunnel not needed."
    return 0
  fi

  : "${host:?missing host}"
  : "${local_port:?missing local port}"
  : "${remote_port:?missing remote port}"
  : "${ssm_target:?missing ssm target}"
  : "${aws_region:?missing aws region}"
  : "${aws_profile:?missing aws profile}"

  if [ "$forbid_prod_host" = "true" ]; then
    local host_lc
    host_lc="$(printf '%s' "$host" | tr '[:upper:]' '[:lower:]')"
    if [[ "$host_lc" == *prod* ]] || [[ "$host_lc" == *production* ]]; then
      echo "ERROR: Refusing to connect to production-like host: $host"
      return 1
    fi
  fi

  echo "Starting ${label} SSM tunnel to ${host}:${remote_port} on localhost:${local_port}"

  if tmux has-session -t "$session" 2>/dev/null; then
    if nc -z 127.0.0.1 "$local_port" 2>/dev/null; then
      echo "${label} tunnel already ready: 127.0.0.1:${local_port}"
      return 0
    fi
    echo "Existing ${label} tunnel session found, but port is closed. Restarting session..."
    tmux kill-session -t "$session" 2>/dev/null || true
  fi

  tmux new-session -d -s "$session" \
    "aws ssm start-session \
      --region '$aws_region' \
      --profile '$aws_profile' \
      --target '$ssm_target' \
      --document-name AWS-StartPortForwardingSessionToRemoteHost \
      --parameters host='$host',portNumber='$remote_port',localPortNumber='$local_port'"

  echo -n "Waiting for ${label} tunnel "
  for _ in {1..30}; do
    if nc -z 127.0.0.1 "$local_port" 2>/dev/null; then
      echo ""
      echo "${label} tunnel ready: 127.0.0.1:${local_port} -> ${host}:${remote_port}"
      return 0
    fi

    if ! tmux has-session -t "$session" 2>/dev/null; then
      echo ""
      echo "ERROR: ${label} tunnel session exited early."
      tmux capture-pane -p -t "$session" -S -120 2>/dev/null || true
      return 1
    fi

    echo -n "."
    sleep 0.5
  done

  echo ""
  echo "ERROR: ${label} tunnel did not open in time."
  tmux capture-pane -p -t "$session" -S -120 2>/dev/null || true
  return 1
}

stop_ssm_tunnel() {
  local session="$1"
  local label="$2"
  local access="$3"
  local host="$4"
  local remote_port="$5"
  local local_port="$6"

  if [ "$access" != "ssm" ]; then
    if [ -n "$host" ] && [ -n "$remote_port" ]; then
      echo "Disconnected (${access}): ${host}:${remote_port}"
    else
      echo "Disconnected (${access})"
    fi
    return 0
  fi

  if tmux has-session -t "$session" 2>/dev/null; then
    tmux kill-session -t "$session" 2>/dev/null || true
    echo "Disconnected (${label}): 127.0.0.1:${local_port} -> ${host}:${remote_port}"
  else
    echo "No active tunnel session to disconnect (${label}): 127.0.0.1:${local_port} -> ${host}:${remote_port}"
  fi
}
