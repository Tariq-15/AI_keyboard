# LanguageTool server

The grammar tier the keyboard talks to. One endpoint, no state, no accounts.

The app ships with **no default server address**. Until someone enters one in settings,
the keyboard makes no network requests for grammar at all and runs on the bundled
on-device spell checker. This deployment is therefore optional infrastructure, not a
dependency.

## What it is

| | |
| --- | --- |
| `docker-compose.yml` | LanguageTool 6.4 plus a Caddy reverse proxy |
| `Caddyfile` | TLS, per-IP rate limiting, and exposing **only** `POST /v2/check` |

LanguageTool's own container serves more than we want public — `/v2/languages`, and an
admin surface. Caddy publishes exactly one route and 404s everything else.

## Deploying

Both free tiers below are sufficient. Oracle's is more generous and always-on; Fly's is
simpler to operate.

### Fly.io

```
fly launch --no-deploy --name chalkboard-grammar
fly scale memory 512
fly deploy
```

`fly.toml` should map `internal_port = 8010` and set `auto_stop_machines = "suspend"`.
A suspended machine takes ~1s to wake, which is acceptable for a key the user taps
deliberately — but *not* acceptable if you ever move grammar checking onto the typing
path. Do not move it onto the typing path.

### Oracle Cloud Free Tier

An Ampere A1 instance (4 OCPU / 24 GB is within the always-free allowance, and wildly
more than this needs — 1 OCPU / 6 GB is plenty):

```
sudo dnf install -y docker docker-compose-plugin
sudo systemctl enable --now docker
git clone <this repo> && cd backend
# point the Caddyfile at your hostname first
sudo docker compose up -d
```

Open ports 80 and 443 in **both** the OCI security list and the instance firewall —
Oracle images ship with `firewalld` blocking them, which is the step people miss:

```
sudo firewall-cmd --permanent --add-service=http --add-service=https
sudo firewall-cmd --reload
```

## Configuration

| Variable | Value here | Why |
| --- | --- | --- |
| `Java_Xmx` | `512m` | Fits the smallest free tiers; a keyboard sends one short request per tap |
| `langtool_languageModel` | empty | The n-gram data improves confusion pairs but needs 8 GB+ of disk |
| Rate limit | 30 requests/min/IP | A person tapping a key cannot exceed this; a script can |

## Verifying

```
curl -s -X POST https://your-host/v2/check \
  --data-urlencode "text=I has a apple" \
  --data-urlencode "language=en-US" | jq '.matches[].replacements[0].value'
```

Should return suggested replacements. Then in the app: **Settings → Grammar → server
address**, and the indicator changes from "Grammar: on-device" to "Grammar: server".

## Privacy

Every request is stateless. Nothing about the text is written to disk, and access logs
record the request line only — never the body, which is the user's text. There is no
database, no analytics, and no user identity: the server cannot associate two requests
with the same person beyond an IP address held in memory for rate limiting.

If you change any of this, the app's privacy policy and both store data-safety
declarations have to change with it. They make this exact claim.
