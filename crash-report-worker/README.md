# EinthusanTV crash report endpoint

This Cloudflare Worker receives a small diagnostic payload from the Android TV app and creates an issue in the configured GitHub repository. The GitHub credential stays in the Worker secret store and is never included in the APK.

## Deploy

1. Create a Cloudflare account and install Wrangler.
2. From this directory, run `wrangler deploy` (or create a Worker in the Cloudflare dashboard and paste `src/index.js`).
3. Set Worker variables:
   - `GITHUB_OWNER` = `Vijaye82`
   - `GITHUB_REPO` = `EinthusanTV`
4. Create a GitHub fine-grained personal access token limited to this repository, with **Issues: Read and write** only. Store it as a Worker secret named `GITHUB_TOKEN` using `wrangler secret put GITHUB_TOKEN`. Never put this token in the Android app, Gradle properties, GitHub Actions variables, or source control.
5. In Cloudflare, add a rate-limiting rule for this Worker: at most 10 POST requests per IP per 10 minutes. Keep the 32 KB request cap in the Worker.
6. Ensure the repository has labels named `bug` and `crash-report` (or adjust the labels in the Worker code).
7. Copy the deployed HTTPS Worker URL, ending in `/` only if that is the route you configured.
8. In GitHub repository Settings → Secrets and variables → Actions → Variables, create `CRASH_REPORT_URL` with the Worker URL.
9. Trigger a new Android TV APK build. The build workflow injects this public endpoint URL into BuildConfig; no GitHub token is injected into the app.

## Payload and privacy

The app sends the diagnostic text, app version/build number, device model/manufacturer and Android version/API level. It does not send Einthusan credentials or session cookies. Crash text is truncated to 24 KB. Review crash reports before sharing them publicly; stack traces can sometimes contain file paths or other diagnostic context.

The Worker validates the payload size and required fields, keeps GitHub credentials server-side, and creates an issue labelled `bug` and `crash-report`. Configure Cloudflare rate limiting before making the endpoint public to reduce spam and abuse.

## Test

After deployment and a new APK build, use the diagnostic screen's **Send crash report to GitHub** button. A successful response displays the created GitHub issue URL. If the endpoint is not configured in Actions, the app clearly reports that the crash-report service is not configured.
