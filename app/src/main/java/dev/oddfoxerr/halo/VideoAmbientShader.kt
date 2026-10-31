package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.min

/**
 * Spatial video bias lighting. Colors are interpolated by actual rounded-perimeter distance;
 * there are no synthetic music gradients or generated particles in Video Ambilight.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class VideoAmbientShader {
    private var runtime: RuntimeShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var failed = false
    internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)

    fun draw(
        canvas: Canvas, width: Int, height: Int, zones: IntArray,
        insetPx: Float, cornerPx: Float, thicknessPx: Float,
        intensity: Float, diffusionPx: Float
    ): Boolean {
        if (failed || !canvas.isHardwareAccelerated || zones.size != VideoEdgeSampler.COUNT) return false
        return try {
            val shader = runtime ?: RuntimeShader(SOURCE).also { runtime = it }
            val frame = RectF(insetPx, insetPx, width-insetPx, height-insetPx)
            val radius = min(cornerPx, min(frame.width(),frame.height())*.5f)
            shader.setFloatUniform("resolution",width.toFloat(),height.toFloat())
            shader.setFloatUniform("frame",frame.left,frame.top,frame.right,frame.bottom)
            shader.setFloatUniform("radius",radius)
            shader.setFloatUniform("thickness",thicknessPx)
            shader.setFloatUniform("diffusion",diffusionPx)
            shader.setFloatUniform("intensity",intensity.coerceIn(.10f,1f))
            for (index in zones.indices) {
                val c = zones[index]
                shader.setFloatUniform("zone$index",
                    Color.red(c)/255f, Color.green(c)/255f, Color.blue(c)/255f)
            }
            paint.shader=shader
            paint.alpha=255
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader=null
            true
        } catch (e: RuntimeException) {
            failed=true
            paint.shader=null
            Log.e("HALO","Video Ambilight shader failed",e)
            false
        }
    }
    private companion object {
        private val SOURCE = """

uniform float2 resolution;
uniform float4 frame;
uniform float radius;
uniform float intensity;
uniform float thickness;
uniform float diffusion;
uniform float3 zone0;
uniform float3 zone1;
uniform float3 zone2;
uniform float3 zone3;
uniform float3 zone4;
uniform float3 zone5;
uniform float3 zone6;
uniform float3 zone7;
uniform float3 zone8;
uniform float3 zone9;
uniform float3 zone10;
uniform float3 zone11;
uniform float3 zone12;
uniform float3 zone13;
uniform float3 zone14;
uniform float3 zone15;

float roundedDistance(float2 p) {
    float2 center = 0.5*(frame.xy+frame.zw);
    float2 extent = max((frame.zw-frame.xy)*.5-radius,float2(1.0));
    float2 q=abs(p-center)-extent;
    return length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-radius;
}
float arcPosition(float2 p) {
    float L=frame.x, T=frame.y, R=frame.z, B=frame.w;
    float r=max(1.0,min(radius,min((R-L)*.5,(B-T)*.5)));
    float h=max(0.0,R-L-2.0*r), v=max(0.0,B-T-2.0*r);
    float a=1.570796327*r;
    float perimeter=max(1.0,2.0*(h+v)+4.0*a);
    float s=0.0;
    if(p.x>=R-r && p.y<=T+r) {
        float angle=atan(p.y-(T+r),p.x-(R-r));
        s=h+(angle+1.570796327)*r;
    } else if(p.x>=R-r && p.y>=B-r) {
        float angle=atan(p.y-(B-r),p.x-(R-r));
        s=h+a+v+angle*r;
    } else if(p.x<=L+r && p.y>=B-r) {
        float angle=atan(p.y-(B-r),p.x-(L+r));
        s=2.0*h+2.0*a+v+(angle-1.570796327)*r;
    } else if(p.x<=L+r && p.y<=T+r) {
        float angle=atan(p.y-(T+r),p.x-(L+r));
        s=2.0*h+3.0*a+2.0*v+(angle+3.14159265)*r;
    } else {
        float dt=abs(p.y-T), dr=abs(p.x-R), db=abs(p.y-B), dl=abs(p.x-L);
        if(dt<=dr && dt<=db && dt<=dl) s=clamp(p.x-(L+r),0.0,h);
        else if(dr<=db && dr<=dl) s=h+a+clamp(p.y-(T+r),0.0,v);
        else if(db<=dl) s=h+a+v+a+clamp((R-r)-p.x,0.0,h);
        else s=2.0*h+3.0*a+v+clamp((B-r)-p.y,0.0,v);
    }
    return fract(s/perimeter);
}
float3 zoneColor(int index) {
    if (index == 0) return zone0;
    if (index == 1) return zone1;
    if (index == 2) return zone2;
    if (index == 3) return zone3;
    if (index == 4) return zone4;
    if (index == 5) return zone5;
    if (index == 6) return zone6;
    if (index == 7) return zone7;
    if (index == 8) return zone8;
    if (index == 9) return zone9;
    if (index == 10) return zone10;
    if (index == 11) return zone11;
    if (index == 12) return zone12;
    if (index == 13) return zone13;
    if (index == 14) return zone14;
    if (index == 15) return zone15;
    return zone0;
}
half4 main(float2 p) {
    float d=roundedDistance(p);
    float width=max(2.0,thickness);
    float outer=width+max(2.0,diffusion)*2.6;
    if(d < -outer*1.4 || d>outer*1.4) return half4(0.0);
    float u=arcPosition(p);
    // Each zone is sampled at (i + .5) / 16 of the same rounded perimeter.
    float cursor=u*16.0-.5;
    float base=floor(cursor);
    int lower=int(mod(base+32.0,16.0));
    int upper=int(mod(base+33.0,16.0));
    float blend=fract(cursor);
    blend=blend*blend*(3.0-2.0*blend);
    float3 color=mix(zoneColor(lower),zoneColor(upper),blend);
    float near=1.0-smoothstep(width*.65,width*1.1,abs(d));
    float aura=exp(-pow(d/max(2.0,outer),2.0)*2.6)*.28;
    float energy=clamp(max(color.r,max(color.g,color.b)),0.0,1.0);
    float visible=smoothstep(.015,.14,energy);
    float alpha=clamp((near*.83+aura)*intensity*visible,0.0,.90);
    return half4(half3(clamp(color,0.0,1.0))*half(alpha),half(alpha));
}

        """.trimIndent()
    }
}
