#!/usr/bin/env bash

# configure docker
eval $(docker-machine env default)

export TRAVIS="true" # way hacky
export TRAVIS_COMMIT="$BUILDKITE_COMMIT"
export TRAVIS_REPO_SLUG="getnelson/nelson"
export TRAVIS_JOB_NUMBER="1.1"
export TRAVIS_BUILD_NUMBER="$BUILDKITE_BUILD_NUMBER"

sbt ++2.11.11 'release with-defaults'
