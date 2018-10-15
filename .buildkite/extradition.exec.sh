#!/usr/bin/env bash

set -e

git config --global user.email "team@getnelson.io"
git config --global user.name "Nelson Team"

if [ "$BUILDKITE_PULL_REQUEST" = 'false' ]; then
	git checkout -qf "$BUILDKITE_BRANCH";
fi

echo "--> cloning getnelson/api repo..."
temp_dir=$(mktemp -d)
git clone git@github.com:getnelson/api.git "${temp_dir}"

echo "--> copying updated files from nelson tree to api tree..."
cp -fvR api/src/main/protobuf/  "${temp_dir}/src/main/protobuf/"


# save those changes
cd "${temp_dir}"
echo "--> commiting changes to api upstream..."
git commit -am "auto-extradition $(date)"
git push origin master

# cleanup the workspace
rm -rf "${temp_dir}"
