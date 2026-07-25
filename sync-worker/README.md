# geflip sync Worker

A tiny free [Cloudflare Worker](https://workers.cloudflare.com/) that stores one JSON
blob per **sync-id** in Workers KV. The geflip web app and the RuneLite plugin both
read/write it over https, so your **config + fills sync anywhere** — phone on mobile
data included. No account token in the apps: the unguessable sync-id *is* the key.

Verified locally (`wrangler dev` + miniflare): GET/PUT round-trip, id validation,
CORS, and the two-writer **shallow merge** (the plugin writes `fills`, the web app
writes `config`, neither clobbers the other).

## Deploy (~5 min, free — one time)

You need Node installed. Everything runs through `npx wrangler` (no global install).

```bash
cd sync-worker

# 1. sign in (opens a browser; a free Cloudflare account is enough)
npx wrangler login

# 2. create the KV store — copy the printed id
npx wrangler kv namespace create GEFLIP

# 3. paste that id into wrangler.toml  ->  id = "…"

# 4. ship it — copy the printed URL (https://geflip-sync.<you>.workers.dev)
npx wrangler deploy
```

## Wire the two apps to it

Pick one **sync-id** (16–64 url-safe chars — the web app's *Generate id* button makes
one). Use the **same** URL + id in both:

- **Web app** → Config → *Cloud sync*: paste the Worker URL and the sync-id, hit *Sync now*.
- **RuneLite plugin** → Geflip settings: *Cloud sync URL* + *Cloud sync id* (same values).

That's it. The plugin pushes your real fills; the web app pushes config; both pull the
merged blob. Keep the sync-id private — anyone who has it can read your book.

## Test it locally first (optional)

```bash
npx wrangler dev            # runs on http://127.0.0.1:8788 with a local KV
curl "http://127.0.0.1:8788/?id=my-test-id-123456"                 # -> {}
curl -X PUT "http://127.0.0.1:8788/?id=my-test-id-123456" -d '{"config":{"bankroll":50000000}}'
curl "http://127.0.0.1:8788/?id=my-test-id-123456"                 # -> your blob
```

## Cost

Cloudflare's free tier is 100k Worker requests/day and plenty of KV ops — a single
flipper syncing every couple minutes uses a rounding error of that. Free.
