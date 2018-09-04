#!/usr/bin/env bash

git config --global user.email "team@getnelson.io"
git config --global user.name "Nelson Team"

# configure docker
export DOCKER_HOST=unix:///var/run/docker.sock

# subvert the sbt-rig plugin
export TRAVIS="true" # way hacky
export TRAVIS_COMMIT="$BUILDKITE_COMMIT"
export TRAVIS_REPO_SLUG="getnelson/nelson"
# NOTE(timperrett): crazy hack;
# for more info see: https://github.com/Verizon/sbt-rig/blob/master/src/main/scala/DocsPlugin.scala#L113
export TRAVIS_JOB_NUMBER="1.999"
export TRAVIS_BUILD_NUMBER="$BUILDKITE_BUILD_NUMBER"

if [ "$BUILDKITE_PULL_REQUEST" = 'false' ]; then
	git checkout -qf "$BUILDKITE_BRANCH";
fi

sbt ++2.11.11 'release with-defaults'
