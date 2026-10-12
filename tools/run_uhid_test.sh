#!/usr/bin/env bash
# Builds and (if permitted) runs the local uhid descriptor validator.
# On success you should see a "DroidPad (test)" touchpad appear to libinput.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cc -O2 -o "$DIR/uhid_test" "$DIR/uhid_test.c" -lm

if [ ! -e /dev/uhid ]; then
    echo "no /dev/uhid; load the module:  sudo modprobe uhid"
fi

if [ -r /dev/uhid ] && [ -w /dev/uhid ]; then
    exec "$DIR/uhid_test" "$DIR/descriptor.hex"
fi

cat <<EOF
/dev/uhid is not writable by $USER. Run one of:

  # one-shot (recommended)
  sudo $DIR/uhid_test $DIR/descriptor.hex

  # or grant your user access permanently (log out/in afterwards)
  echo 'KERNEL=="uhid", GROUP="input", MODE="0660"' | \\
      sudo tee /etc/udev/rules.d/99-uhid-droidpad.rules
  sudo udevadm control --reload && sudo udevadm trigger /dev/uhid

Then inspect in other terminals:
  libinput list-devices | grep -A8 -i droidpad
  udevadm info /dev/input/eventX | grep -i ID_INPUT_TOUCHPAD
  sudo libinput debug-events --show-keycodes
  sudo libinput debug-gui
EOF
