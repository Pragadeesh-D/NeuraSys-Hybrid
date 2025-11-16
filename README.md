## 🧠 Scoring Logic
Logs are scored based on:
- Severity (INFO < WARN < ERROR)
- Keywords (e.g., "exception", "fail", "timeout")
- Recency (boost for logs < 5 minutes old)

## 🌐 Webhook Support
Send logs via webhook to the bot — no manual commands needed.

## 🛡️ Rate Limiting
Limits log posts to 20 per minute per channel to prevent spam floods.

## 🧪 Demo Steps
1. Add the extension to a channel
2. Run `/toggleFilter on`
3. Post logs from `sampleLogs.txt`
4. Watch duplicates suppressed, anomalies highlighted, and sensitive info masked
