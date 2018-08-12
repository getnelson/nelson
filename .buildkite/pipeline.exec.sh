#!/usr/bin/env bash

# configure docker
eval $(docker-machine env default)

sbt test
