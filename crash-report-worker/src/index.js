const MAX_BODY_BYTES = 32 * 1024;

export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    };
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (request.method !== "POST") return json({ error: "Method not allowed" }, 405, cors);
    if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO) {
      return json({ error: "Crash report service is not configured" }, 503, cors);
    }
    const length = Number(request.headers.get("Content-Length") || 0);
    if (length > MAX_BODY_BYTES) return json({ error: "Report is too large" }, 413, cors);
    let input;
    try { input = await request.json(); } catch { return json({ error: "Invalid JSON" }, 400, cors); }
    const report = typeof input.report === "string" ? input.report.slice(0, 24000) : "";
    if (!report.trim()) return json({ error: "Missing crash report" }, 400, cors);
    const appVersion = safe(input.appVersion, 40);
    const buildNumber = safe(input.buildNumber, 20);
    const device = safe(input.device, 100);
    const androidVersion = safe(input.androidVersion, 40);
    const sdkInt = safe(input.sdkInt, 10);
    const title = ("[Crash] EinthusanTV " + (appVersion || "unknown") + " on " + (device || "TV device")).slice(0, 240);
    const body = [
      "## Crash report",
      "",
      "| Field | Value |",
      "|---|---|",
      "| App version | " + (appVersion || "unknown") + " |",
      "| Build | " + (buildNumber || "unknown") + " |",
      "| Device | " + (device || "unknown") + " |",
      "| Android | " + (androidVersion || "unknown") + " (SDK " + (sdkInt || "unknown") + ") |",
      "",
      "## Diagnostic details",
      "",
      "```text",
      report.replace(/```/g, "` ` `"),
      "```",
      "",
      "_Submitted automatically by the EinthusanTV crash reporter. Do not add credentials, cookies or personal account data to reports._"
    ].join("\n");
    const api = "https://api.github.com/repos/" + encodeURIComponent(env.GITHUB_OWNER) + "/" + encodeURIComponent(env.GITHUB_REPO) + "/issues";
    const gh = await fetch(api, {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + env.GITHUB_TOKEN,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "EinthusanTV-Crash-Reporter",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ title, body, labels: ["bug", "crash-report"] })
    });
    const result = await gh.json().catch(() => ({}));
    if (!gh.ok) return json({ error: "GitHub rejected the report (HTTP " + gh.status + ")" }, 502, cors);
    return json({ ok: true, issueUrl: result.html_url || "" }, 201, cors);
  }
};

function safe(value, max) {
  return String(value ?? "").replace(/[\r\n|]/g, " ").slice(0, max);
}
function json(value, status, headers) {
  return new Response(JSON.stringify(value), { status, headers });
}
