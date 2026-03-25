#!/usr/bin/env bash
set -euo pipefail
mkdir -p out
javac --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED -d out $(find src/main/java -name '*.java')
