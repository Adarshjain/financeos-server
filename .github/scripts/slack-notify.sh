#!/usr/bin/env bash
# Posts a deploy status message to Slack. The same script lives in financeos-server and
# financeos-client — keep the two copies identical.
# Usage: slack-notify.sh <started|success|failure|cancelled>
# Requires SLACK_WEBHOOK_URL and DEPLOY_COMPONENT ("Server" / "Client").
# Optional: DEPLOY_START_TS (epoch secs, for duration), DEPLOY_SHA / DEPLOY_SUBJECT (default:
# checked-out commit), DEPLOY_LINK_URL + DEPLOY_LINK_LABEL (default: this Actions run),
# DEPLOY_ACTOR (default: GITHUB_ACTOR), DEPLOY_FAILURE_HINT (extra line on failure).
set -euo pipefail

status="${1:?usage: slack-notify.sh <started|success|failure|cancelled>}"
component="${DEPLOY_COMPONENT:?DEPLOY_COMPONENT must be set (Server or Client)}"

# A missing secret must be visible in the run summary, not an invisibly skipped step.
if [ -z "${SLACK_WEBHOOK_URL:-}" ]; then
  echo "::warning title=Slack notification skipped::SLACK_WEBHOOK_URL is not set on this repository. Add it under Settings -> Secrets and variables -> Actions -> Repository secrets."
  exit 0
fi

case "$status" in
  started)   emoji=":rocket:"                title="$component deployment started"   color="#3b82f6" ;;
  success)   emoji=":white_check_mark:"      title="$component deployment succeeded" color="#2eb886" ;;
  failure)   emoji=":x:"                     title="$component deployment failed"    color="#e01e5a" ;;
  cancelled) emoji=":black_square_for_stop:" title="$component deployment cancelled" color="#6b7280" ;;
  *)         emoji=":grey_question:"         title="$component deployment $status"   color="#6b7280" ;;
esac

duration=""
if [ "$status" != "started" ] && [ -n "${DEPLOY_START_TS:-}" ]; then
  secs=$(( $(date +%s) - DEPLOY_START_TS ))
  duration=" ($((secs / 60))m $((secs % 60))s)"
fi

sha="${DEPLOY_SHA:-$GITHUB_SHA}"
sha_short="${sha:0:7}"
link_url="${DEPLOY_LINK_URL:-$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID}"
link_label="${DEPLOY_LINK_LABEL:-view run}"
actor="${DEPLOY_ACTOR:-$GITHUB_ACTOR}"
subject="${DEPLOY_SUBJECT:-$(git log -1 --pretty=%s 2>/dev/null || true)}"
subject="${subject%%$'\n'*}"

text="$emoji *$title*$duration — \`$GITHUB_REPOSITORY\`"
text="$text"$'\n'"\`$sha_short\` on \`$GITHUB_REF_NAME\` · by \`$actor\` · <$link_url|$link_label>"
if [ -n "$subject" ]; then
  text="$text"$'\n'">$subject"
fi
if [ "$status" = "failure" ] && [ -n "${DEPLOY_FAILURE_HINT:-}" ]; then
  text="$text"$'\n'":warning: $DEPLOY_FAILURE_HINT"
fi

# jq builds the JSON so commit subjects with quotes/newlines can't break the payload.
payload="$(jq -n \
  --arg text "$text" \
  --arg color "$color" \
  --arg fallback "$title — $GITHUB_REPOSITORY@$sha_short" \
  '{
     text: $fallback,
     attachments: [
       { color: $color,
         blocks: [ { type: "section", text: { type: "mrkdwn", text: $text } } ] }
     ]
   }')"

curl -sS --fail-with-body -X POST \
  -H 'Content-Type: application/json' \
  --data "$payload" \
  "$SLACK_WEBHOOK_URL"
