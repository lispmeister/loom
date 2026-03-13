#!/usr/bin/env bash
set -euo pipefail

echo "==> Building lab release..."
npx shadow-cljs release lab

echo "==> Building container image..."
container build -t loom-lab:latest .

echo "==> Done. Image: loom-lab:latest"
container image list | grep loom-lab
