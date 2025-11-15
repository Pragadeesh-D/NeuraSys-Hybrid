const fs = require("fs");

// Simple JS versions of your Deluge functions for local testing
function maskSensitive(log) {
  return log
    .replace(/(Bearer|Token)\s+[A-Za-z0-9\-\._]+/gi, "[REDACTED_TOKEN]")
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[REDACTED_EMAIL]")
    .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, "[REDACTED_IP]")
    .replace(/https?:\/\/[^\s]+/g, "[REDACTED_URL]")
    .replace(/(\/[\w\-.]+)+/g, "[REDACTED_PATH]");
}

let dedupMap = new Map();
let freqMap = new Map();

function logFilter(message) {
  const now = Date.now();

  // Deduplication
  if (dedupMap.has(message)) {
    const lastSeen = dedupMap.get(message);
    if (now - lastSeen < 60000) {
      return { action: "suppress", reason: "duplicate", message };
    }
  }
  dedupMap.set(message, now);

  // Frequency tracking
  const count = (freqMap.get(message) || 0) + 1;
  freqMap.set(message, count);

  if (count >= 5) {
    return { action: "highlight", reason: "anomaly", message };
  }

  return { action: "pass", reason: "new", message };
}

// Run test
const logs = fs.readFileSync("test/sampleLogs.txt", "utf-8").split("\n");

logs.forEach((log) => {
  const masked = maskSensitive(log);
  const result = logFilter(masked);
  console.log(`[${result.action.toUpperCase()}] ${result.reason}: ${result.message}`);
});
