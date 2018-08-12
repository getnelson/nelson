#!/usr/bin/env bash

# configure docker
eval $(docker-machine env default)

sbt ++2.11.11 'release with-defaults'
