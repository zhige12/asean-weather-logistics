#!/usr/bin/env bash
set -e
if command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg already: $(ffmpeg -version | head -1)"
else
  echo "installing ffmpeg..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq ffmpeg >/tmp/ffmpeg_install.log 2>&1 \
    && echo "installed: $(ffmpeg -version | head -1)" \
    || { echo "INSTALL FAILED"; tail -30 /tmp/ffmpeg_install.log; }
fi
