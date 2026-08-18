#!/usr/bin/env bash
# Posts a deploy status message to Slack. Called from .github/workflows/deploy.yml.
# Usage: slack-notify.sh <started|success|failure|cancelled>
# Requires SLACK_WEBHOOK_URL; reads DEPLOY_START_TS (epoch secs) to report duration.
set -euo pipefail

status="${1:?usage: slack-notify.sh <started|success|failure|cancelled>}"

# A missing secret must be visible in the run summary, not an invisibly skipped step.
if [ -z "${SLACK_WEBHOOK_URL:-}" ]; then
  echo "::warning title=Slack notification skipped::SLACK_WEBHOOK_URL is not set on this repository. Add it under Settings -> Secrets and variables -> Actions -> Repository secrets."
  exit 0
fi

case "$status" in
  started)   emoji=":rocket:"               title="Deploy started"    color="#3b82f6" ;;
  success)   emoji=":white_check_mark:"     title="Deploy succeeded"  color="#2eb886" ;;
  failure)   emoji=":x:"                    title="Deploy failed"     color="#e01e5a" ;;
  cancelled) emoji=":black_square_for_stop:" title="Deploy cancelled" color="#6b7280" ;;
  *)         emoji=":grey_question:"        title="Deploy $status"    color="#6b7280" ;;
esac

duration=""
if [ "$status" != "started" ] && [ -n "${DEPLOY_START_TS:-}" ]; then
  secs=$(( $(date +%s) - DEPLOY_START_TS ))
  duration=" ($((secs / 60))m $((secs % 60))s)"
fi

sha_short="${GITHUB_SHA:0:7}"
run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
subject="$(git log -1 --pretty=%s 2>/dev/null || true)"

text="$emoji *$title*$duration — \`$GITHUB_REPOSITORY\`"
text="$text"$'\n'"\`$sha_short\` on \`$GITHUB_REF_NAME\` · by \`$GITHUB_ACTOR\` · <$run_url|view run>"
if [ -n "$subject" ]; then
  text="$text"$'\n'">$subject"
fi
if [ "$status" = "failure" ]; then
  text="$text"$'\n'":warning: Rollback is manual — \`backend-1.0.0.jar.prev\` is on the VM."
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
