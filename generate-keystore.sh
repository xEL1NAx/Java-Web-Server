#!/usr/bin/env bash
set -euo pipefail

keytool -genkeypair \
  -alias java-web-server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost, OU=Dev, O=Dev, L=Local, ST=Local, C=US"

echo "Created keystore.p12 in the project root"
