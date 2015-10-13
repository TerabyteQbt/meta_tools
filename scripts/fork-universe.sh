#!/bin/bash


if [[ $# -lt 2 ]]; then
    echo "Usage: $0 SRC_ORG DST_ORG [repos...]"
    echo
    echo "    If repos are left off, it will grab them all"
    exit 1;
fi

SRC_ORG=$1
shift
DST_ORG=$1
shift

if [[ "$@" == "" ]]; then
    REPOS=$(curl "https://api.github.com/users/$SRC_ORG/repos" | grep git_url | perl -wlne "s:.*$SRC_ORG/(.*).git:\$1:; s/\",//g; print")
else
    REPOS="$@"
fi

echo "Using repos: $REPOS"
for i in $REPOS; do
    curl -X POST -v --netrc "https://api.github.com/repos/$SRC_ORG/$i/forks?organization=$DST_ORG";
done

