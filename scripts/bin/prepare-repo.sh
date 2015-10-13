#!/bin/bash

set -e

function usage {
    echo "Usage: $0 [ORG|USER] ORG_OR_USER_NAME REPO_NAME"
    echo
	echo "    Ensure your ~/.netrc contains an entry for api.github.com as well"
    exit 1;
}

if [[ $# -lt 3 ]]; then
	usage;
fi

SWITCH=$1
shift
ORG_NAME=$1
shift
REPO_NAME=$1
shift

if [[ "$SWITCH" == "ORG" ]]; then
	URL="https://api.github.com/orgs/$ORG_NAME/repos"
elif [[ "$SWITCH" == "USER" ]]; then
	URL="https://api.github.com/user/repos"
else
	usage;
fi

# check to see if repo exists, create if it doesn't
JSON="{\"name\":\"$REPO_NAME\"}"
curl --fail -s https://api.github.com/repos/$ORG_NAME/$REPO_NAME || curl --fail --netrc -X POST -H "Content-Type: application/json" -d "$JSON" "$URL"

# push the stub to the repo
# http://stackoverflow.com/questions/4774054/reliable-way-for-a-bash-script-to-get-the-full-path-to-itself
pushd `dirname $0` > /dev/null
SCRIPT_PATH=`pwd -P`
DATA_TAR="$(dirname $SCRIPT_PATH)/data/qbt-stub.tar.gz"
# http://unix.stackexchange.com/questions/30091/fix-or-alternative-for-mktemp-in-os-x
TMPDIR=`mktemp -d 2>/dev/null || mktemp -d -t 'mytmpdir'`
pushd $TMPDIR > /dev/null
tar -xzf $DATA_TAR && (cd qbt-stub && git push git@github.com:$ORG_NAME/$REPO_NAME +HEAD:master)
popd > /dev/null
popd > /dev/null
