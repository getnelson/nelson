#!/usr/bin/env bash

git config --global user.email "team@getnelson.io"
git config --global user.name "Nelson Team"

if [ "$BUILDKITE_PULL_REQUEST" = 'false' ]; then
	git checkout -qf "$BUILDKITE_BRANCH";
fi

temp_dir=$(mktemp -d)
git clone git@github.com:getnelson/api.git "${temp_dir}"
cp -fR api/src/main/protobuf/  "${temp_dir}/src/main/protobuf/"

# save those changes
git commit -am "auto-extradition $(date)"
git push origin master

# cleanup the workspace
rm -rf "${temp_dir}"
