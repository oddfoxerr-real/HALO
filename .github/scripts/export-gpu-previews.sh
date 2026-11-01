#!/usr/bin/env bash
set -euo pipefail

mkdir -p gpu-previews
exported=0

# The emulator-runner executes each YAML script line separately. Keep loops in
# one standalone script so preview exports never break a successful GPU test.
for design in electric_arcs twin_pulse light_beads corner_trace; do
  for phase in t0 t1; do
    frame="${design}_${phase}.png"
    target="gpu-previews/${frame}"
    if adb exec-out run-as dev.oddfoxerr.halo cat "files/halo-previews/${frame}" > "${target}" &&
      test -s "${target}"; then
      exported=$((exported + 1))
    else
      rm -f "${target}"
      echo "::warning::HALO GPU frame ${frame} was not available after instrumentation; renderer tests still passed."
    fi
  done
done

echo "Exported ${exported}/8 GPU-rendered preview images"
