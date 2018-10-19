#!/usr/bin/env bash

echo "==>> initilizing doctl..."
doctl auth init -t "${DIGITAL_OCEAN_API_TOKEN}"

echo "==>> deleting the droplet..."
doctl compute droplet list | grep -v 'ID' | sort -r -k1 | grep "buildkite-worker" | awk '{print $1}' | xargs -L1 doctl compute droplet delete -f
