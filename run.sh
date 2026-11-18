#!/usr/bin/env bash
# 151 Grabber — avvio: ./run.sh
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
if [[ -z "${QT_QPA_PLATFORMTHEME:-}" \
      && -e /usr/lib/qt6/plugins/platformthemes/KDEPlasmaPlatformTheme6.so ]]; then
    export QT_QPA_PLATFORMTHEME=kde
fi
exec python3 app.py "$@"
