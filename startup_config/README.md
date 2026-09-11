# Startup config

Use a config file with `./start.sh --config <file>` (or `./start.sh <file>`).

The format is a flat list of `key=value` lines. Comments start with `#`. Blank lines are ignored. Do not quote values.

## Shared keys

These apply to every component unless that component overrides them:

| Key | Meaning | Default |
| --- | --- | --- |
| `host` | Hostname clients and the console use to reach servers | `localhost` |
| `port` | Base port of the replica group | `9000` |
| `scheduler` | Reconfiguration schedule (`A`, `B`, or `C`) | `A` |

## Servers

Started from `src/server` as:

`mvn exec:java -Dexec.args="{port} {id} {scheduler}"`

Replica `id` runs from `0` to `count - 1`. The process binds `{port} + {id}`.

| Key | Meaning | Default |
| --- | --- | --- |
| `server.count` | How many server processes to start | `5` |
| `server.port` | Base port (overrides `port`) | shared `port` |
| `server.scheduler` | Schedule letter (overrides `scheduler`) | shared `scheduler` |

## App (clients)

Started from `src/app` as:

`mvn exec:java -Dexec.args="{id} {host} {port} {scheduler}"`

Client ids start at `app.id` and increase by one for each extra client. The first client runs in the current terminal; any further clients are opened in new terminals when possible.

| Key | Meaning | Default |
| --- | --- | --- |
| `app.count` | How many clients to start | `1` |
| `app.id` | First client id (`1`–`99`) | `1` |
| `app.host` | Server host (overrides `host`) | shared `host` |
| `app.port` | Base port (overrides `port`) | shared `port` |
| `app.scheduler` | Schedule letter (overrides `scheduler`) | shared `scheduler` |

## Console

Started from `src/console` as:

`mvn exec:java -Dexec.args="{host} {port} {scheduler}"`

If at least one client is started, the console is opened in a new terminal when possible. If there are no clients, it runs in the current terminal.

| Key | Meaning | Default |
| --- | --- | --- |
| `console.enabled` | `true` / `false` (also `yes`/`no`, `1`/`0`) | `true` |
| `console.host` | Server host (overrides `host`) | shared `host` |
| `console.port` | Base port (overrides `port`) | shared `port` |
| `console.scheduler` | Schedule letter (overrides `scheduler`) | shared `scheduler` |

## Example

See `example.conf` in this folder. A minimal file that only changes the base port:

```
port=9100
server.count=5
app.count=1
console.enabled=true
```
