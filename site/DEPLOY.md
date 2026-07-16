# Deploying scalascript.dev

The site is a single static page — `index.html` + `favicon.svg`, no build step, no
framework, no external requests (CSS and JS are inlined). Serve it from any static
host. Recommended: **Cloudflare Pages** (free, global CDN, automatic HTTPS — which
`.dev` requires).

## 1 · Register the domain — Cloudflare Registrar

- Register `scalascript.dev` at <https://dash.cloudflare.com> → **Registrar**
  (~$12–15/yr, at-cost, free WHOIS privacy).
- `.dev` is on the HSTS preload list, so HTTPS is mandatory — Cloudflare provisions
  and renews the certificate automatically; nothing to configure.
- Optional brand protection: also register `scalascript.com` / `scalascript.org` and
  redirect them to the primary.

## 2 · Before making the repo public — one-time safety gate

The repository is going public. Public is effectively irreversible (clones, forks,
and archives persist). Before flipping visibility, run a **full git-history** secret
scan — not just the current tree:

```bash
brew install gitleaks
gitleaks detect --source . --redact -v      # scans every commit in history
```

Only after a clean report: GitHub → **Settings → General → Danger Zone → Change
repository visibility → Public**.

## 3 · Deploy the site — Cloudflare Pages

### Option A — direct upload (fastest, no repo wiring)

```bash
npm i -g wrangler
wrangler pages deploy site --project-name scalascript
```

### Option B — connect the Git repo (auto-deploys on every push)

Cloudflare dashboard → **Pages → Create → Connect to Git** → select this repo, then:

| Setting | Value |
|---|---|
| Framework preset | None |
| Build command | *(leave empty)* |
| Build output directory | `site` |
| Root directory | `/` |

## 4 · Attach the domain

Pages project → **Custom domains** → add `scalascript.dev` (and `www.scalascript.dev`).
With the domain already on Cloudflare, DNS records and the TLS certificate are created
automatically.

## Result

`https://scalascript.dev` — static, HTTPS, on a global CDN, at zero running cost.

## Files

| File | Purpose |
|---|---|
| `index.html` | The landing page — self-contained; inline CSS/JS, no external assets |
| `favicon.svg` | The wordmark mark (red rounded square, white “S”) |
