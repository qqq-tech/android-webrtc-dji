#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: ${0##*/} [-d DOMAIN] [-o OUTPUT_DIR]

Generate a self-signed TLS certificate and private key suitable for local
 testing. The script requires OpenSSL to be installed.

Options:
  -d DOMAIN      Primary domain to include in the certificate's Subject
                 Alternative Name (default: localhost)
  -o OUTPUT_DIR  Directory where the certificate and key will be written
                 (default: certs)
  -h             Show this help message

  The resulting files are named server.crt and server.key within OUTPUT_DIR.
USAGE
}

DOMAIN="localhost"
OUTPUT_DIR="certs"

while getopts ":d:o:h" opt; do
  case "$opt" in
    d)
      DOMAIN="$OPTARG"
      ;;
    o)
      OUTPUT_DIR="$OPTARG"
      ;;
    h)
      usage
      exit 0
      ;;
    ?)
      echo "Unknown option: -$OPTARG" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v openssl >/dev/null 2>&1; then
  echo "Error: openssl is required to generate certificates." >&2
  exit 1
fi

OUTPUT_DIR=$(mkdir -p "$OUTPUT_DIR" && cd "$OUTPUT_DIR" && pwd)
CRT_PATH="$OUTPUT_DIR/server.crt"
KEY_PATH="$OUTPUT_DIR/server.key"
CONFIG_PATH="$OUTPUT_DIR/openssl.cnf"
trap 'rm -f "$CONFIG_PATH"' EXIT

cat >"$CONFIG_PATH" <<CONFIG
[ req ]
default_bits       = 2048
distinguished_name = req_distinguished_name
req_extensions     = v3_req
prompt             = no

[ req_distinguished_name ]
C  = KR
ST = Demo
L  = Demo
O  = Demo
OU = Demo
CN = $DOMAIN

[ v3_req ]
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = $DOMAIN
DNS.2 = localhost
IP.1  = 127.0.0.1
IP.2  = ::1
CONFIG

openssl req \
  -x509 \
  -nodes \
  -days 365 \
  -newkey rsa:2048 \
  -keyout "$KEY_PATH" \
  -out "$CRT_PATH" \
  -config "$CONFIG_PATH" >/dev/null 2>&1

echo "Generated certificate: $CRT_PATH"
echo "Generated private key: $KEY_PATH"
