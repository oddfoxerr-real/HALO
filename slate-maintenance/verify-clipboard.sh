#!/usr/bin/env bash
set -euo pipefail

# Run from the restored standalone Slate project. Failures in compilation or test
# setup do not count as reproducing the original clipboard regression.
source_file=app/src/main/java/com/odd/slatekeyboard/ClipboardStore.kt
evidence=maintenance-evidence
mkdir -p "$evidence"
fixed_file=$(mktemp)
cp "$source_file" "$fixed_file"
trap 'cp "$fixed_file" "$source_file"; rm -f "$fixed_file"' EXIT
cp "$RUNNER_TEMP/slate-original-ClipboardStore.kt" "$source_file"

set +e
gradle --no-daemon --console=plain :app:testDebugUnitTest \
  --tests '*ClipboardStoreTest.inFlightImageSurvivesTextCaptureFromAnotherStore' \
  > "$evidence/before.log" 2>&1
baseline_status=$?
set -e
cp "$fixed_file" "$source_file"
if [ "$baseline_status" -eq 0 ]; then
  echo 'The regression did not reproduce on the original code.'
  exit 1
fi

python3 - <<'PY'
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
report = Path('app/build/test-results/testDebugUnitTest/TEST-com.odd.slatekeyboard.ClipboardStoreTest.xml')
assert report.is_file(), 'Original-code test did not run; inspect before.log'
root = ET.parse(report).getroot()
assert root.attrib['tests'] == '1' and root.attrib['failures'] == '1', root.attrib
failure = root.find('testcase/failure')
assert failure is not None and 'Image must survive concurrent text capture' in failure.attrib.get('message', ''), ET.tostring(root)
shutil.copyfile(report, 'maintenance-evidence/before.xml')
print('CONFIRMED: original ClipboardStore loses the in-flight image when another store captures text.')
PY

gradle --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
cp -r app/build/test-results/testDebugUnitTest "$evidence/after"
