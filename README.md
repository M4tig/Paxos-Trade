# DidaTrade

Please read: [Project architecture: Steps 1–2 design overview](docs/overview-steps-1-2.md) to get an overview of our implementation.

## Install [just](https://just.systems/man/en/packages.html)

- macOS: `brew install just`
- Linux: `sudo apt install just` on Debian 13 and Ubuntu 24.04. Otherwise `cargo install just`.
- Windows: `winget install --id Casey.Just --exact`

## Run

From the repository root:

```bash
just init
just quickstart
```

`just init` installs the local Java and Maven toolchain in a local dev environment, then builds. `just quickstart` starts 3 replicas on `localhost:9000`, one client, and the console, using schedule A.

`just` lists every recipe. `just start` asks for host, port, and counts instead of using those defaults. `just start --config startup_config/example.conf` uses a config file. `just reload` rebuilds and starts the default cluster again.

`just test` runs the test suite. `just test-integration` runs only the cluster tests, and `just test-e2e` runs the process-level scripts. `just pre-commit` runs lint, build, and test.