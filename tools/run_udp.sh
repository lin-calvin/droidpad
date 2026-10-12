#!/usr/bin/env bash
# Builds and runs the UDP -> uhid bridge. Needs /dev/uhid access.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${1:-9999}"
cc -O2 -o "$DIR/uhid_udp" "$DIR/uhid_udp.c"

if [ -r /dev/uhid ] && [ -w /dev/uhid ]; then
    exec "$DIR/uhid_udp" "$PORT"
fi
cat <<EOF
/dev/uhid is not writable by $USER. Run:

  sudo $DIR/uhid_udp $PORT

Or grant access permanently (log out/in afterwards):
  echo 'KERNEL=="uhid", GROUP="input", MODE="0660"' | \\
      sudo tee /etc/udev/rules.d/99-uhid-droidpad.rules
  sudo udevadm control --reload && sudo udevadm trigger /dev/uhid

Then, in the droidpad app enter "<this-PC-ip>:$PORT" and press the UDP button.
Verify with:  libinput list-devices | grep -i -A3 droidpad
Note: allow UDP $PORT through your firewall if one is active.
EOF
