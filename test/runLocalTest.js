const fs = require("fs");

let dedupMap = new Map();
let freqMap = new Map();
let rateLimiter = { count: 0, windowStart: Date.now() };

function maskSensitive(log) {
  return log
    .replace(/(Bearer|Token)\s+[A-Za-z0-9\-\._]+/gi, "[REDACTED_TOKEN]")
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[REDACTED_EMAIL]")
    .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, "[REDACTED_IP]")
    .replace(/https?:\/\/[^\s]+/g, "[REDACTED_URL]")
    .replace(/(\/[\w\-.]+)+/g, "[REDACTED_PATH]");
}

function scoreLog(log) {
  let score = 0;
  if (log.includes("ERROR")) score += 3;
  else if (log.includes("WARN")) score += 2;
  else if (log.includes("INFO")) score += 1;

  if (/exception|fail|timeout|crash/i.test(log)) score += 2;

  const ageMinutes = 0; // Simulate recent logs
  if (ageMinutes < 5) score += 1;

  return score;
}

function rateLimit() {
  const now = Date.now();
  if (now - rateLimiter.windowStart > 60000) {
    rateLimiter.windowStart = now;
    rateLimiter.count = 1;
    return true;
  } else {
    if (rateLimiter.count >= 20) return false;
    rateLimiter.count++;
    return true;
  }
}

function logFilter(message) {
  const now = Date.now();

  // Frequency tracking first
  const count = (freqMap.get(message) || 0) + 1;
  freqMap.set(message, count);

  if (count >= 5) {
    return { action: "highlight", reason: "anomaly", message };
  }

  // Deduplication second
  if (dedupMap.has(message)) {
    const lastSeen = dedupMap.get(message);
    if (now - lastSeen < 60000) {
      return { action: "suppress", reason: "duplicate", message };
    }
  }
  dedupMap.set(message, now);

  // Default pass
  return { action: "pass", reason: "new", message };
}

// Run test
const logs = fs.readFileSync("test/sampleLogs.txt", "utf-8").split("\n");

logs.forEach((log) => {
  const masked = maskSensitive(log);
  const score = scoreLog(masked); // always calculate score

  const displayMessage = masked.length > 0 ? masked : "[EMPTY LOG AFTER MASKING]";

  if (!rateLimit()) {
    console.log(`[RATE LIMITED] | Score: ${score} | ${displayMessage}`);
    return;
  }

  const result = logFilter(masked);
  console.log(`[${result.action.toUpperCase()}] ${result.reason} | Score: ${score} | ${displayMessage}`);
});
