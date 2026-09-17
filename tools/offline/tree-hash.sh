#!/bin/bash
# One hash for the text files of the tree. Run it on both sides (device_bash in $HOME/mnt/itemcasino,
# and the sandbox copy): equal hashes mean nothing drifted. PNGs are left out because the commit
# tool re-encodes them (same pixels, different bytes).
cd "${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
find src tools ./*.md -type f ! -name '*.png' | sed 's#^\./##' | LC_ALL=C sort | xargs md5sum | md5sum | cut -d' ' -f1
