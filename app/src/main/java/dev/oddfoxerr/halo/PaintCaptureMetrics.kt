package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Diagnostic counters only: never retain or export captured pixels. */
internal object PaintCaptureMetrics {
    private val mutableReport = MutableStateFlow("No capture test has run.")
    val report = mutableReport.asStateFlow()
    private var callbacks=0L
    private var valid=0L
    private var black=0L
    private var changed=0L
    private var painted=0L
    private var dropped=0L
    private var failed=0L
    private var previousHash: Long?=null
    private var lastInput=0L
    private var lastPaint=0L
    private var lastPublish=0L
    private var mode="Off"
    private var reason="Idle"

    @Synchronized fun begin(probe: Boolean) {
        callbacks=0;valid=0;black=0;changed=0;painted=0;dropped=0;failed=0
        previousHash=null;lastInput=0;lastPaint=0;lastPublish=0
        mode=if(probe) "Capture only (no overlay)" else "Capture + painter"
        reason="Running"
        publish(true)
    }
    @Synchronized fun onFrame(frame: Bitmap?) {
        callbacks++
        lastInput=SystemClock.uptimeMillis()
        if(frame==null) black++ else {
            valid++
            val hash=fingerprint(frame)
            if(previousHash != null && previousHash != hash) changed++
            previousHash=hash
        }
        publish(false)
    }
    @Synchronized fun onDropped() { dropped++;publish(false) }
    @Synchronized fun onPainted(success: Boolean) {
        if(success) {painted++;lastPaint=SystemClock.uptimeMillis()}
        else failed++
        publish(false)
    }
    @Synchronized fun onEvent(description:String) {reason=description.take(120);publish(true)}

    @Synchronized fun current():String {
        val now=SystemClock.uptimeMillis()
        val inputAge=if(lastInput==0L) "none" else (now-lastInput).coerceAtLeast(0L).toString()+" ms ago"
        val paintAge=if(lastPaint==0L) "none" else (now-lastPaint).coerceAtLeast(0L).toString()+" ms ago"
        val diagnosis=when {
            reason!="Running" -> reason
            callbacks==0L -> "No ImageReader frames received"
            valid==0L -> "Capture black or redacted"
            callbacks>15L && changed==0L -> "Frames arrive, but pixels never change"
            painted==0L && mode.contains("painter") -> "Capture works, painter not presenting"
            else -> "Monitoring"
        }
        return "Mode: "+mode+
            "\nCapture callbacks: "+callbacks+
            "\nValid: "+valid+" | Black: "+black+" | Changed: "+changed+
            "\nPainted: "+painted+" | Dropped: "+dropped+" | Render failures: "+failed+
            "\nLatest input: "+inputAge+" | Latest paint: "+paintAge+
            "\nState: "+diagnosis
    }
    @Synchronized private fun publish(force:Boolean) {
        val now=SystemClock.uptimeMillis()
        if(force || now-lastPublish>=1000L) {
            lastPublish=now
            mutableReport.value=current()
        }
    }
    private fun fingerprint(frame:Bitmap):Long {
        var state=0xcbf29ce484222325UL.toLong()
        for(iy in 0 until 10) for(ix in 0 until 8) {
            val x=((ix+.5f)*frame.width/8f).toInt().coerceIn(0,frame.width-1)
            val y=((iy+.5f)*frame.height/10f).toInt().coerceIn(0,frame.height-1)
            state=(state xor frame.getPixel(x,y).toLong())*0x100000001b3L
        }
        return state
    }
}
