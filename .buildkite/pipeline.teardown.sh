#!/usr/bin/env bash

doctl auth init -t "${DIGITAL_OCEAN_API_TOKEN}"

doctl compute droplet delete buildkite-worker
