#!/usr/bin/env bash
set -euo pipefail
java --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED -cp out server.Main src/main/resources/server-config.json
