#!/usr/bin/env bash
set -euo pipefail

# push-grafana-config.sh — Idempotently provisions Grafana contact points, dashboards, and alert rules via HTTP API.
# Usage:
#   GRAFANA_URL="https://<instance>.grafana.net" GRAFANA_SA_TOKEN="glsa_..." SLACK_WEBHOOK_URL="https://hooks.slack.com/..." ./push-grafana-config.sh [--dry-run]

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  echo "[INFO] Running in --dry-run mode. No changes will be pushed to Grafana."
fi

if [[ -z "${GRAFANA_URL:-}" || -z "${GRAFANA_SA_TOKEN:-}" ]]; then
  echo "[ERROR] Missing required environment variables: GRAFANA_URL and GRAFANA_SA_TOKEN." >&2
  echo "[ERROR] Usage: GRAFANA_URL=\"https://instance.grafana.net\" GRAFANA_SA_TOKEN=\"glsa_...\" SLACK_WEBHOOK_URL=\"https://...\" $0 [--dry-run]" >&2
  exit 1
fi

if [[ "${DRY_RUN}" == "false" && -z "${SLACK_WEBHOOK_URL:-}" ]]; then
  echo "[ERROR] Missing required environment variable: SLACK_WEBHOOK_URL." >&2
  echo "[ERROR] SLACK_WEBHOOK_URL is required to provision the slack-financeos alert contact point." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTACT_POINTS_DIR="${SCRIPT_DIR}/contact-points"
DASHBOARDS_DIR="${SCRIPT_DIR}/dashboards"
ALERTS_DIR="${SCRIPT_DIR}/alerts"

echo "[INFO] Querying Grafana datasources from ${GRAFANA_URL}/api/datasources..."
DATASOURCES_JSON=$(curl -sS -f -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" "${GRAFANA_URL}/api/datasources" || {
  echo "[ERROR] Failed to fetch datasources from Grafana API." >&2
  exit 1
})

# Datasource selection. A Grafana Cloud stack ships SEVERAL loki/prometheus datasources
# (logs, usage-insights, alert-state-history, usage...). Picking the first match silently
# points every dashboard and LogQL alert at the wrong store, so:
#   1. an explicit *_DS_UID env override always wins
#   2. otherwise prefer the canonical application datasource by uid
#   3. otherwise, if exactly one candidate exists use it; if several, fail loudly
pick_ds() {
  local type="$1" preferred="$2" override="$3"
  if [[ -n "${override}" ]]; then echo "${override}"; return 0; fi
  local exact
  exact=$(echo "${DATASOURCES_JSON}" | jq -r --arg t "$type" --arg p "$preferred" \
    '.[] | select(.type==$t) | select(.uid==$p) | .uid' | head -n1)
  if [[ -n "${exact}" ]]; then echo "${exact}"; return 0; fi
  local all count
  all=$(echo "${DATASOURCES_JSON}" | jq -r --arg t "$type" '.[] | select(.type==$t) | .uid')
  count=$(echo "${all}" | grep -c . || true)
  if [[ "${count}" -eq 1 ]]; then echo "${all}"; return 0; fi
  return 1
}

LOKI_UID=$(pick_ds loki       grafanacloud-logs   "${GRAFANA_LOKI_DS_UID:-}")   || LOKI_UID=""
PROM_UID=$(pick_ds prometheus grafanacloud-prom   "${GRAFANA_PROM_DS_UID:-}")   || PROM_UID=""
TEMPO_UID=$(pick_ds tempo     grafanacloud-traces "${GRAFANA_TEMPO_DS_UID:-}")  || TEMPO_UID=""

if [[ -z "${LOKI_UID}" || -z "${PROM_UID}" ]]; then
  echo "[ERROR] Could not unambiguously resolve the Loki and/or Prometheus datasource." >&2
  echo "[ERROR] Set GRAFANA_LOKI_DS_UID / GRAFANA_PROM_DS_UID explicitly. Candidates found:" >&2
  echo "${DATASOURCES_JSON}" | jq -r '.[] | select(.type=="loki" or .type=="prometheus") | " - \(.type)  uid=\(.uid)  name=\(.name)"' >&2
  exit 1
fi

if [[ -z "${TEMPO_UID}" ]]; then
  echo "[WARN] Tempo datasource not resolved — tracing panels will use fallback UID."
  TEMPO_UID="tempo"
fi

echo "[INFO] Resolved Datasource UIDs -> Loki: ${LOKI_UID}, Prometheus: ${PROM_UID}, Tempo: ${TEMPO_UID}"

# 1. Provision Contact Points Idempotently (A2)
EXISTING_CP_JSON="[]"
if [[ "${DRY_RUN}" == "false" ]]; then
  echo "[INFO] Querying existing contact points from ${GRAFANA_URL}/api/v1/provisioning/contact-points..."
  EXISTING_CP_RESPONSE=$(curl -sS -w "\n%{http_code}" -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" "${GRAFANA_URL}/api/v1/provisioning/contact-points")
  cp_http_code=$(echo "${EXISTING_CP_RESPONSE}" | tail -n1)
  cp_body=$(echo "${EXISTING_CP_RESPONSE}" | sed '$d')

  if [[ "${cp_http_code}" -ge 200 && "${cp_http_code}" -lt 300 ]]; then
    EXISTING_CP_JSON="${cp_body}"
  else
    echo "[ERROR] Failed to fetch existing contact points from Grafana API (HTTP ${cp_http_code})." >&2
    exit 1
  fi
fi

for cp_file in "${CONTACT_POINTS_DIR}"/*.json; do
  [[ -f "${cp_file}" ]] || continue
  cp_name=$(basename "${cp_file}")
  echo "[INFO] Processing contact point ${cp_name}..."

  cp_content=$(cat "${cp_file}" | sed "s|\${SLACK_WEBHOOK_URL}|${SLACK_WEBHOOK_URL:-}|g")
  cp_uid=$(echo "${cp_content}" | jq -r '.uid // .name')

  cp_exists=$(echo "${EXISTING_CP_JSON}" | jq -r --arg uid "${cp_uid}" '.[] | select(.uid==$uid or .name==$uid) | .uid')

  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "[DRY-RUN] Would upsert contact point '${cp_uid}' to ${GRAFANA_URL}/api/v1/provisioning/contact-points"
  elif [[ -n "${cp_exists}" && "${cp_exists}" != "null" ]]; then
    # Contact point exists -> PUT update in-place
    response=$(curl -sS -w "\n%{http_code}" -X PUT -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${cp_content}" \
      "${GRAFANA_URL}/api/v1/provisioning/contact-points/${cp_uid}")

    http_code=$(echo "${response}" | tail -n1)

    if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
      echo "[SUCCESS] Contact point '${cp_uid}' updated (HTTP ${http_code})."
    else
      echo "[ERROR] Failed to update contact point '${cp_uid}' (HTTP ${http_code})." >&2
      exit 1
    fi
  else
    # Contact point new -> POST create
    response=$(curl -sS -w "\n%{http_code}" -X POST -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${cp_content}" \
      "${GRAFANA_URL}/api/v1/provisioning/contact-points")

    http_code=$(echo "${response}" | tail -n1)

    if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
      echo "[SUCCESS] Contact point '${cp_uid}' created (HTTP ${http_code})."
    else
      echo "[ERROR] Failed to create contact point '${cp_uid}' (HTTP ${http_code})." >&2
      exit 1
    fi
  fi
done

# Ensure folder 'FinanceOS'
if [[ "${DRY_RUN}" == "true" ]]; then
  echo "[DRY-RUN] Would create folder 'FinanceOS'"
else
  echo "[INFO] Creating/ensuring folder 'FinanceOS'..."
  curl -sS -X POST -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"title":"FinanceOS","uid":"financeos-folder"}' \
    "${GRAFANA_URL}/api/folders" > /dev/null || true
fi

# 2. Provision Dashboards
for db_file in "${DASHBOARDS_DIR}"/*.json; do
  [[ -f "${db_file}" ]] || continue
  db_name=$(basename "${db_file}")
  echo "[INFO] Processing dashboard ${db_name}..."

  db_content=$(cat "${db_file}" | \
    sed "s/\${DS_LOKI}/${LOKI_UID}/g" | \
    sed "s/\${DS_PROM}/${PROM_UID}/g" | \
    sed "s/\${DS_TEMPO}/${TEMPO_UID}/g")

  payload=$(jq -n --argjson db "${db_content}" '{"dashboard": $db, "folderUid": "financeos-folder", "overwrite": true}')

  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "[DRY-RUN] Would post dashboard ${db_name} to ${GRAFANA_URL}/api/dashboards/db"
  else
    response=$(curl -sS -w "\n%{http_code}" -X POST -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${payload}" \
      "${GRAFANA_URL}/api/dashboards/db")

    http_code=$(echo "${response}" | tail -n1)
    body=$(echo "${response}" | sed '$d')

    if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
      echo "[SUCCESS] Dashboard ${db_name} pushed successfully (HTTP ${http_code})."
    else
      echo "[ERROR] Failed to push dashboard ${db_name} (HTTP ${http_code}): ${body}" >&2
      exit 1
    fi
  fi
done

# 3. Provision Alert Rules Idempotently (A1)
EXISTING_RULES_JSON="[]"
if [[ "${DRY_RUN}" == "false" ]]; then
  echo "[INFO] Querying existing alert rules from ${GRAFANA_URL}/api/v1/provisioning/alert-rules..."
  EXISTING_RULES_RESPONSE=$(curl -sS -w "\n%{http_code}" -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" "${GRAFANA_URL}/api/v1/provisioning/alert-rules")
  rules_http_code=$(echo "${EXISTING_RULES_RESPONSE}" | tail -n1)
  rules_body=$(echo "${EXISTING_RULES_RESPONSE}" | sed '$d')

  if [[ "${rules_http_code}" -ge 200 && "${rules_http_code}" -lt 300 ]]; then
    EXISTING_RULES_JSON="${rules_body}"
  else
    echo "[ERROR] Failed to fetch existing alert rules from Grafana API (HTTP ${rules_http_code}). Cannot safely determine alert rule upsert state." >&2
    exit 1
  fi
fi

for alert_file in "${ALERTS_DIR}"/*.json; do
  [[ -f "${alert_file}" ]] || continue
  alert_name=$(basename "${alert_file}")
  echo "[INFO] Processing alert rules ${alert_name}..."

  alert_content=$(cat "${alert_file}" | \
    sed "s/\${DS_LOKI}/${LOKI_UID}/g" | \
    sed "s/\${DS_PROM}/${PROM_UID}/g" | \
    sed "s/\${DS_TEMPO}/${TEMPO_UID}/g")

  groups=$(echo "${alert_content}" | jq -c '.groups[]')
  while read -r group; do
    group_name=$(echo "${group}" | jq -r '.name')
    rules=$(echo "${group}" | jq -c '.rules[]')
    while read -r rule; do
      rule_title=$(echo "${rule}" | jq -r '.title')
      rule_uid=$(echo "${rule}" | jq -r '.uid')
      rule_payload=$(echo "${rule}" | jq --arg folder "financeos-folder" --arg grp "${group_name}" '. + {"folderUID": $folder, "ruleGroup": $grp}')

      # Check if rule uid already exists
      rule_exists=$(echo "${EXISTING_RULES_JSON}" | jq -r --arg uid "${rule_uid}" '.[] | select(.uid==$uid) | .uid')

      if [[ "${DRY_RUN}" == "true" ]]; then
        echo "[DRY-RUN] Would upsert alert rule '${rule_title}' (uid: ${rule_uid})"
      elif [[ -n "${rule_exists}" && "${rule_exists}" != "null" ]]; then
        # Rule exists -> PUT to update in-place
        response=$(curl -sS -w "\n%{http_code}" -X PUT -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
          -H "Content-Type: application/json" \
          -d "${rule_payload}" \
          "${GRAFANA_URL}/api/v1/provisioning/alert-rules/${rule_uid}")

        http_code=$(echo "${response}" | tail -n1)
        body=$(echo "${response}" | sed '$d')

        if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
          echo "[SUCCESS] Alert rule '${rule_title}' (${rule_uid}) updated (HTTP ${http_code})."
        else
          echo "[ERROR] Failed to update alert rule '${rule_title}' (HTTP ${http_code}): ${body}" >&2
          exit 1
        fi
      else
        # Rule new -> POST to create
        response=$(curl -sS -w "\n%{http_code}" -X POST -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
          -H "Content-Type: application/json" \
          -d "${rule_payload}" \
          "${GRAFANA_URL}/api/v1/provisioning/alert-rules")

        http_code=$(echo "${response}" | tail -n1)
        body=$(echo "${response}" | sed '$d')

        if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
          echo "[SUCCESS] Alert rule '${rule_title}' (${rule_uid}) created (HTTP ${http_code})."
        else
          echo "[ERROR] Failed to create alert rule '${rule_title}' (HTTP ${http_code}): ${body}" >&2
          exit 1
        fi
      fi
    done <<< "${rules}"
  done <<< "${groups}"
done

echo "[INFO] Grafana configuration provisioning complete."
