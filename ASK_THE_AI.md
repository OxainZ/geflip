# ASK THE AI — your account, live, in any Claude session

The coach plugin now pushes a full **account snapshot** to your sync Worker
every ~5 minutes while you play (same Worker, same sync-id the flipper
already uses — nothing new to configure). The flipper was already pushing
your **fills, open offers, ranked flips, and session P&L**. Together, one
GET gives an AI everything:

```
curl -s "https://YOUR-WORKER.workers.dev/?id=YOUR_SYNC_ID"
```

Top-level keys in the blob:

| key       | writer   | contents                                              |
|-----------|----------|-------------------------------------------------------|
| `account` | coach    | skills/levels, total, combat, QP, quests in progress, gp, net worth, CA tier, slayer points/streak, WOM EHP/EHB + hours-to-max, top goals with their gaps |
| `session` | flipper  | realized profit, deployed gp, win %, avg hold, gp/day |
| `fills`   | flipper  | your last 3,000 GE fills                              |
| `offers`  | flipper  | the 8 GE slots right now                              |
| `flips`   | flipper  | the scanner's current ranked flips (gp/hr, tax priced)|
| `config`  | web app  | your web-app settings                                 |

## How to use it

**From a Claude session (this one included):** paste your Worker URL +
sync-id once and say "read my account and tell me what to do next." The AI
fetches the blob and advises from live state — restocks, which goal is
actually closest, whether your GE slots are working or idle, what the
scanner's best flip is for YOUR cash stack.

**From the Telegram bridge:** same thing — message it the curl line once;
it can re-fetch whenever you ask "what should I do tonight?"

A good standing prompt:

> Fetch my geflip blob. Given my cash stack, open GE slots, and the current
> ranked flips, tell me: (1) which slots to fill and with what buy/sell
> prices, (2) the single best non-GE money maker my stats unlock right now,
> (3) which coach goal is closest to ready and its exact next step.

## The rules this respects

- **Read-only toward the game.** The plugin ships state OUT; nothing in
  geflip automates input. The AI advises; you click. That is the line
  RuneLite-sanctioned plugins live on, and this stays on the right side.
- **The sync-id is the only secret.** Anyone with it can read your account
  snapshot — treat it like a password, never commit it, and rotate it (new
  id in plugin + web app) if it leaks. This file deliberately contains no
  real id.
- Blank `Cloud sync URL/id` in the plugin settings = the whole lane is off.

## Honest expectations

The profit still comes from the same place it always did: the scanner's
tax-priced gp/hr ranking and your buy limits. What the AI lane adds is that
an advisor can finally see YOUR side — cash, slots, stats, goals — so its
advice is about your account, not generic. "Tell me what to do next" now
has a real answer; "make me rich" still doesn't.
