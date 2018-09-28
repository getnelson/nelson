#!/usr/bin/env bash

echo "==>> initilizing doctl..."
doctl auth init -t "${DIGITAL_OCEAN_API_TOKEN}"

echo "==>> deleting the droplet..."
doctl compute droplet delete -f buildkite-worker
