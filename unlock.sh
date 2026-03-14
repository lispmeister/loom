#!/bin/sh

# Get the Keygrip list like this
# gpg --list-secret-keys --with-keygrip --keyid-format long
# We want the key for:
# Markus Fix (loom project) <168889+lispmeister@users.noreply.github.com>

KEYGRIP=939DD9D73F8986AE3691309F2EC92DDCEF35CC0B

echo "unlock test" | gpg --sign -u $KEYGRIP >/dev/null
