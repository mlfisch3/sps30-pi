#!/usr/bin/env bash
# Standard SPS30 test protocol.
#
#   ./run_tests.sh software   # codec + driver tests, no sensor (run anywhere)
#   ./run_tests.sh hardware   # + hardware-in-the-loop tests (run on the Pi)
#   ./run_tests.sh all        # alias for hardware
#
# Extra args are forwarded to pytest, e.g.:
#   ./run_tests.sh hardware --port /dev/ttyUSB0 -v
set -euo pipefail
cd "$(dirname "$0")"

mode="${1:-software}"
shift || true
py="python3"; command -v pytest >/dev/null 2>&1 && py="pytest" || py="python3 -m pytest"

case "$mode" in
  software) exec $py -m "not hardware" "$@" ;;
  hardware|all) exec $py --run-hardware "$@" ;;
  *) echo "usage: $0 [software|hardware|all] [pytest args...]" >&2; exit 2 ;;
esac
