# Cliqtrix Log Filter

A Zoho Cliq extension that filters noisy logs, masks sensitive information, and highlights anomalies in real time. Built for **Cliqtrix 2025**, this bot helps teams debug faster, share cleaner logs, and avoid spam floods.

## 🚀 Features
- ✅ Real-time log filtering in Cliq channels
- 🔒 Privacy-safe masking of tokens, IPs, emails, URLs, and file paths
- 🔁 Deduplication to suppress repeated logs
- ⚠️ Anomaly detection for frequent errors
- 🧠 Configurable thresholds per channel
- 🛡️ Rate limiter to prevent spam floods
- 🧪 Local test harness with sample logs

## 📦 Components
- `bots/logBot.deluge` — listens to channel messages and applies filtering
- `commands/toggleFilter.deluge` — enables/disables filtering per channel
- `commands/configFilter.deluge` — adjusts deduplication and anomaly thresholds
- `services/logFilter.deluge` — core filtering logic
- `utils/maskSensitive.deluge` — redacts sensitive info
- `utils/rateLimiter.deluge` — limits message rate per channel
- `test/sampleLogs.txt` — noisy logs for testing
- `test/runLocalTest.js` — Node.js script to simulate masking and filtering

## 🧪 Demo Steps
1. Add the extension to a Cliq channel
2. Post noisy logs (e.g., from `sampleLogs.txt`)
3. Run `/toggleFilter on` to enable filtering
4. Run `/configFilter window=30 threshold=3` to adjust sensitivity
5. Watch duplicates get suppressed and anomalies highlighted
6. Sensitive info will be masked automatically

## 🏆 Contest Goals
- Showcase modular design and privacy-first filtering
- Demonstrate reproducibility via local test harness
- Impress judges with clean commit history and professional repo hygiene

---

Built by Pragadeesh for Cliqtrix 2025 🚀
