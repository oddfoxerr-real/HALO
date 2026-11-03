#!/usr/bin/env python3
"""Apply ItemLens tools to the unpacked source. Fails closed if source changes."""
from pathlib import Path
import shutil

root = Path("app/src/main/java/com/oddfinder/itemlens")
for filename in ("MeasureToolkit.kt", "HandPinchDetector.kt"):
    shutil.copyfile(Path("itemlens-toolkit") / filename, root / filename)

main = root / "MainActivity.kt"
source = main.read_text()
needle = "        PinOverlay(state.pins)"
assert source.count(needle) == 1, "FinderScreen changed; review toolkit placement"
source = source.replace(needle, needle + "\n        MeasureToolkitUi()")
needle = "    Box(modifier = Modifier.fillMaxSize().background(Night)) {"
assert source.count(needle) == 1, "FinderScreen root changed"
source = source.replace(needle, "    val measureState by MeasureToolkit.state.collectAsState()\n" + needle)
needle = "        if (!state.calibrated) {"
assert source.count(needle) == 1, "Marker prompt changed"
source = source.replace(needle, "        if (!state.calibrated && measureState.mode == MeasureMode.OFF) {")
main.write_text(source)

engine = root / "CameraEngine.kt"
source = engine.read_text()
needle = "    private var lastRotation = -1"
assert source.count(needle) == 1, "CameraEngine properties changed"
source = source.replace(needle, needle + "\n    private var pinchDetector: HandPinchDetector? = null")

needle = "                                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE"
assert source.count(needle) == 1, "ARCore configuration changed"
source = source.replace(needle, needle + """
                                if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                    depthMode = Config.DepthMode.AUTOMATIC
                                }""")

needle = "        originPose = null\n        status.publish(CameraState(message = \"Camera paused\"))"
assert source.count(needle) == 1, "ARCore stop sequence changed"
source = source.replace(needle, """        originPose = null
        MeasureToolkit.resetTracking()
        status.publish(CameraState(message = "Camera paused"))""")

needle = "            session?.close()\n            session = null"
assert source.count(needle) == 1, "ARCore dispose sequence changed"
source = source.replace(needle, """            pinchDetector?.close()
            pinchDetector = null
            session?.close()
            session = null""")

needle = "            updateFrame(active, frame)"
assert source.count(needle) == 1, "AR frame render sequence changed"
source = source.replace(needle, """
            val time = SystemClock.uptimeMillis()
            val tracking = frame.camera.trackingState == TrackingState.TRACKING
            if (tracking && MeasureToolkit.mode != MeasureMode.OFF && MeasureToolkit.gesturesEnabled) {
                if (pinchDetector == null) pinchDetector = HandPinchDetector(context)
                val hand = pinchDetector?.detect(context, frame, time)
                if (hand != null) {
                    val from = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    from.put(hand.x).put(hand.y)
                    from.position(0)
                    val to = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, from, Coordinates2d.VIEW, to)
                    val hx = to.get(0)
                    val hy = to.get(1)
                    if (hx.isFinite() && hy.isFinite()) {
                        MeasureToolkit.reportHand(hx, hy, hand.touching, time)
                        if (hand.justPressed) MeasureToolkit.addAt(hx, hy)
                    }
                }
            }
            MeasureToolkit.onRenderFrame(frame, frame.camera, widthPx, heightPx, tracking, time)
            updateFrame(active, frame)
""".strip("\n"))
engine.write_text(source)

gradle = Path("app/build.gradle.kts")
source = gradle.read_text()
needle = '    implementation("com.google.ar:core:1.56.0")'
assert source.count(needle) == 1, "ARCore dependency changed"
source = source.replace(needle, needle + '\n    implementation("com.google.mediapipe:tasks-vision:0.10.32")')
source = source.replace('versionCode = 1', 'versionCode = 2')
source = source.replace('versionName = "0.1.0"', 'versionName = "0.2.0"')
gradle.write_text(source)

print("ItemLens 0.2.0 measuring tools and pinch detector integrated.")
