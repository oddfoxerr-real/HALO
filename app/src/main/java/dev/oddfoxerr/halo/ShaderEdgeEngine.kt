package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.max

/** A single opaque-free AGSL pipeline; there is no legacy Canvas animation fallback. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ShaderEdgeEngine {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var runtime: RuntimeShader? = null
    private var failure: String? = null
    val status: String get() = when {
        failure != null -> "GPU shader unavailable: ${failure}"
        runtime != null -> "AGSL GPU active"
        else -> "GPU initialising"
    }
    internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)

    fun render(
        canvas: Canvas, width: Int, height: Int, bounds: RectF,
        radius: Float, mode: EdgeMode, gradient: EdgeGradient,
        bands: FloatArray, bass: Float, phase: Float, seconds: Float,
        thickness: Float, spread: Float, glow: Float, brightness: Float,
        dotDensity: Float, dotSize: Float, primary: Int, secondary: Int
    ): Boolean {
        if (failure != null || width <= 0 || height <= 0 || !canvas.isHardwareAccelerated) return false
        return try {
            val shader = runtime ?: RuntimeShader(SOURCE).also { runtime = it }
            shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("frame", bounds.left, bounds.top, bounds.right, bounds.bottom)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("seconds", seconds % 3600f)
            shader.setFloatUniform("phase", phase)
            shader.setFloatUniform("bass", bass.coerceIn(0f, 1f))
            shader.setFloatUniform("thickness", thickness.coerceAtLeast(.5f))
            shader.setFloatUniform("spread", spread.coerceIn(.3f, 2.8f))
            shader.setFloatUniform("glow", glow.coerceIn(0f, 1f))
            shader.setFloatUniform("brightness", brightness.coerceIn(.2f, 1f))
            shader.setFloatUniform("density", dotDensity.coerceIn(.4f, 2.5f))
            shader.setFloatUniform("dotSize", dotSize.coerceIn(.4f, 2.4f))
            shader.setIntUniform("design", mode.ordinal)
            shader.setIntUniform("gradientMode", gradient.ordinal)
            val frequencies = FloatArray(4)
            for (i in bands.indices) {
                val bin = (i * 4 / max(1, bands.size)).coerceIn(0, 3)
                frequencies[bin] = max(frequencies[bin], bands[i].coerceIn(0f, 1f))
            }
            shader.setFloatUniform("audio", frequencies)
            shader.setFloatUniform("first", Color.red(primary) / 255f,
                Color.green(primary) / 255f, Color.blue(primary) / 255f)
            shader.setFloatUniform("second", Color.red(secondary) / 255f,
                Color.green(secondary) / 255f, Color.blue(secondary) / 255f)
            paint.shader = shader
            paint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            true
        } catch (exception: RuntimeException) {
            failure = exception.message?.take(180) ?: exception.javaClass.simpleName
            Log.e("HALO", "AGSL edge shader failed; legacy drawing will not be shown", exception)
            paint.shader = null
            false
        }
    }

    private companion object {
        private val SOURCE = """
            // 3D simplex noise adapted from Daniel Sandner's Audio Shader Studio
            // shaders/geometric-patterns/fluidic-1.glsl (MIT, Copyright 2025 Daniel Sandner).
            // See THIRD_PARTY_NOTICES.md for the license and upstream URL.
            float3 mod289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
            float4 mod289_4(float4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
            float4 permute4(float4 x) { return mod289_4(((x * 34.0) + 1.0) * x); }
            float4 inverseSqrt4(float4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
            float noise3(float3 v) {
                float2 C = float2(1.0 / 6.0, 1.0 / 3.0);
                float4 D = float4(0.0, 0.5, 1.0, 2.0);
                float3 i = floor(v + dot(v, C.yyy));
                float3 x0 = v - i + dot(i, C.xxx);
                float3 g = step(x0.yzx, x0.xyz);
                float3 l = 1.0 - g;
                float3 i1 = min(g.xyz, l.zxy);
                float3 i2 = max(g.xyz, l.zxy);
                float3 x1 = x0 - i1 + C.xxx;
                float3 x2 = x0 - i2 + C.yyy;
                float3 x3 = x0 - D.yyy;
                i = mod289_3(i);
                float4 p = permute4(permute4(permute4(
                    i.z + float4(0.0, i1.z, i2.z, 1.0)) +
                    i.y + float4(0.0, i1.y, i2.y, 1.0)) +
                    i.x + float4(0.0, i1.x, i2.x, 1.0));
                float n_ = .142857142857;
                float3 ns = n_ * D.wyz - D.xzx;
                float4 j = p - 49.0 * floor(p * ns.z * ns.z);
                float4 x_ = floor(j * ns.z);
                float4 y_ = floor(j - 7.0 * x_);
                float4 x = x_ * ns.x + ns.yyyy;
                float4 y = y_ * ns.x + ns.yyyy;
                float4 h = 1.0 - abs(x) - abs(y);
                float4 b0 = float4(x.xy, y.xy);
                float4 b1 = float4(x.zw, y.zw);
                float4 s0 = floor(b0) * 2.0 + 1.0;
                float4 s1 = floor(b1) * 2.0 + 1.0;
                float4 sh = -step(h, float4(0.0));
                float4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
                float4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
                float3 p0 = float3(a0.xy, h.x);
                float3 p1 = float3(a0.zw, h.y);
                float3 p2 = float3(a1.xy, h.z);
                float3 p3 = float3(a1.zw, h.w);
                float4 norm = inverseSqrt4(float4(
                    dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
                p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
                float4 m = max(0.6 - float4(
                    dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
                m *= m;
                return 42.0 * dot(m * m,
                    float4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
            }
            
            

uniform float2 resolution;
uniform float4 frame;
uniform float radius;
uniform float seconds;
uniform float phase;
uniform float bass;
uniform float4 audio;
uniform float thickness;
uniform float spread;
uniform float glow;
uniform float brightness;
uniform float3 first;
uniform float3 second;
uniform float density;
uniform float dotSize;
uniform int design;
uniform int gradientMode;
const float TAU = 6.28318530718;
const float HALF_PI = 1.57079632679;

float hash21(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}
float roundedDistance(float2 p) {
    float2 center = .5 * (frame.xy + frame.zw);
    float2 extent = max((frame.zw - frame.xy) * .5 - radius, float2(1.0));
    float2 q = abs(p-center) - extent;
    return length(max(q, float2(0.0))) + min(max(q.x,q.y),0.0) - radius;
}
// Arc-length parameterization of the real rounded rectangle. Unlike atan(center),
// this places dots at uniform distances along straight sides and around corners;
// gradients return to the same color at u=0/1, so there is no hard seam.
float edgePosition(float2 p) {
    float L=frame.x, T=frame.y, R=frame.z, B=frame.w;
    float r=max(1.0,min(radius,min((R-L)*.5,(B-T)*.5)));
    float h=max(0.0,R-L-2.0*r), v=max(0.0,B-T-2.0*r);
    float a=HALF_PI*r, perimeter=max(1.0,2.0*(h+v)+4.0*a);
    float s=0.0;
    if (p.x>=R-r && p.y<=T+r) {
        float angle=atan(p.y-(T+r),p.x-(R-r));
        s=h+(angle+HALF_PI)*r;
    } else if (p.x>=R-r && p.y>=B-r) {
        float angle=atan(p.y-(B-r),p.x-(R-r));
        s=h+a+v+angle*r;
    } else if (p.x<=L+r && p.y>=B-r) {
        float angle=atan(p.y-(B-r),p.x-(L+r));
        s=2.0*h+2.0*a+v+(angle-HALF_PI)*r;
    } else if (p.x<=L+r && p.y<=T+r) {
        float angle=atan(p.y-(T+r),p.x-(L+r));
        s=2.0*h+3.0*a+2.0*v+(angle+3.14159265)*r;
    } else {
        float dt=abs(p.y-T), dr=abs(p.x-R);
        float db=abs(p.y-B), dl=abs(p.x-L);
        if (dt<=dr && dt<=db && dt<=dl) s=clamp(p.x-(L+r),0.0,h);
        else if (dr<=db && dr<=dl) s=h+a+clamp(p.y-(T+r),0.0,v);
        else if (db<=dl) s=h+a+v+a+clamp((R-r)-p.x,0.0,h);
        else s=2.0*h+3.0*a+v+clamp((B-r)-p.y,0.0,v);
    }
    return fract(s/perimeter);
}
float edgeLength() {
    float r=max(1.0,min(radius,min((frame.z-frame.x)*.5,(frame.w-frame.y)*.5)));
    return max(1.0,2.0*((frame.z-frame.x-2.0*r)+(frame.w-frame.y-2.0*r))+TAU*r);
}
float band(float u) {
    float v=fract(u);
    float lo=mix(audio.x,audio.y,smoothstep(0.0,.50,v));
    float hi=mix(audio.z,audio.w,smoothstep(.50,1.0,v));
    return clamp(mix(lo,hi,smoothstep(.17,.83,v)),0.0,1.0);
}
float repeatDistance(float x) { return abs(fract(x+.5)-.5); }
float circleMask(float x,float y,float size) {
    return 1.0-smoothstep(max(.2,size-1.2),size+1.2,length(float2(x,y)));
}
float gaussian(float x,float sigma) {
    float n=x/max(1.0,sigma);
    return exp(-n*n*1.6);
}
float3 palette(float u,float t) {
    float mixPosition=.5-.5*cos(TAU*u);
    if(gradientMode==1) mixPosition=.5-.5*cos(TAU*(u-t*.065));
    if(gradientMode==2) mixPosition=0.0;
    // Blend in linear light so saturated palettes do not turn muddy midway.
    float3 firstLinear=pow(clamp(first,0.0,1.0),float3(2.2));
    float3 secondLinear=pow(clamp(second,0.0,1.0),float3(2.2));
    float3 color=pow(mix(firstLinear,secondLinear,mixPosition),float3(.45454545));
    if(gradientMode==3) {
        float3 chroma=.5+.5*cos(TAU*(u+t*.045)+float3(0.0,2.0943951,4.1887902));
        color=mix(color,chroma,.82);
    }
    return color;
}
half4 main(float2 p) {
    float d=roundedDistance(p);
    float maxExtent=clamp(35.0*thickness*spread+65.0,80.0,230.0);
    if(abs(d)>maxExtent) return half4(0.0);
    float u=edgePosition(p);
    float edge=edgeLength();
    float e=clamp(band(u),0.0,1.0);
    float beat=clamp(bass,0.0,1.0);
    float t=seconds;
    float body=0.0, bloom=0.0, sparkle=0.0;
    float offset=0.0;
    float base=max(1.0,thickness);
    float count=max(12.0,round(56.0*density));
    float dotRadius=clamp((1.7+3.4*e+2.5*beat)*dotSize*base,1.3,22.0);
    if (design==0) { // Liquid silk: domain-warped coherent fabric, not strokes
        float n=noise3(float3(u*9.0,d*.03,t*.22));
        float fold=noise3(float3(u*19.0,d*.055+3.0,t*.29));
        float center=(n*.7+fold*.3)*(8.0+17.0*e)*spread;
        float w=(4.0+13.0*e+4.0*beat)*base*spread;
        body=gaussian(d-center,w);
        bloom=gaussian(d-center,w*3.5)*.23;
        sparkle=pow(max(0.0,fold),5.0)*e*.47;
    } else if(design==1) { // Dots: rows of independently pulsing circular lights
        float cell=floor(u*count+.5);
        float arc=repeatDistance(u*count)*edge/count;
        float row=floor(clamp(-d/(9.0*base)+.5,0.0,2.0));
        float rowPosition=-row*8.5*base;
        float light=hash21(float2(cell,row));
        float radiusLocal=dotRadius*(.58+.42*light);
        float pulse=.68+.32*sin(t*3.1+cell*.44+row);
        body=circleMask(arc,d-rowPosition,radiusLocal)*pulse;
        bloom=gaussian(length(float2(arc,d-rowPosition)),radiusLocal*2.5)*(.14+.18*glow);
        sparkle=body*max(0.0,e)*.30;
    } else if(design==2) { // Constellations: irregular star nodes connected optically
        float cells=max(22.0,round(42.0*density));
        float cell=floor(u*cells);
        float centerU=(cell+.5)/cells;
        float arc=repeatDistance((u-centerU)*cells)*edge/cells;
        float star=hash21(float2(cell,3.1));
        float radial=(star-.5)*26.0*base;
        float distance=length(float2(arc,d-radial));
        float r=(1.4+star*3.0+e*2.8)*base*dotSize;
        float twinkle=.62+.38*sin(t*(1.3+star*3.3)+star*8.0);
        body=circleMask(arc,d-radial,r)*twinkle;
        bloom=gaussian(distance,r*5.0)*.28;
        sparkle=body*.65;
    } else if(design==3) { // Fireflies: drifting randomly offset micro-lights
        float cells=max(20.0,round(52.0*density));
        float su=fract(u-t*.020);
        float cell=floor(su*cells);
        float arc=repeatDistance(su*cells)*edge/cells;
        float r1=hash21(float2(cell,2.0));
        float r2=hash21(float2(cell,7.0));
        float radial=(r1-.5)*43.0*base+sin(t*(.8+r2)+cell)*6.0*base;
        float dis=length(float2(arc,d-radial));
        float size=(1.3+r2*3.7+beat*2.8)*base*dotSize;
        body=circleMask(arc,d-radial,size);
        bloom=gaussian(dis,size*4.8)*(.13+.19*glow);
        sparkle=body*(.3+.7*r2);
    } else if(design==4) { // Meteor trails: three directional energy streaks
        float trail=0.0;
        for(int i=0;i<3;i++) {
            float center=fract(t*(.12+.025*float(i))+float(i)/3.0);
            float behind=fract(center-u+1.0);
            float head=exp(-pow(repeatDistance(u-center)*edge/(20.0*base),2.0));
            float tail=exp(-behind*(13.0+4.0*beat));
            trail=max(trail,head+tail*.60);
        }
        body=gaussian(d,4.0*base+6.0*e)*trail;
        bloom=gaussian(d,24.0*base)*trail*.20;
        sparkle=trail*.38;
    } else if(design==5) { // Ribbons: two counter-flowing layers
        float a=noise3(float3(u*7.0,d*.025,t*.22));
        float b=noise3(float3(u*7.0+4.0,d*.025,-t*.18));
        float r1=(a*14.0-9.0)*base, r2=(b*15.0+10.0)*base;
        body=max(gaussian(d-r1,(2.9+e*8.0)*base),gaussian(d-r2,(2.0+e*5.0)*base)*.65);
        bloom=max(gaussian(d-r1,27.0*base),gaussian(d-r2,25.0*base))*.15;
        sparkle=pow(max(0.0,a+b),4.0)*.27*e;
    } else if(design==6) { // Aurora: wide turbulence curtains with internal texture
        float n=noise3(float3(u*9.0,d*.025,t*.14));
        float m=noise3(float3(u*20.0+3.0,d*.045,t*.30));
        float curtain=(n*.72+m*.28)*(12.0+21.0*e)*spread;
        float w=(7.0+19.0*e+7.0*beat)*base*spread;
        body=gaussian(d-curtain,w)*(.65+.25*m);
        bloom=gaussian(d-curtain,w*3.0)*.30;
        sparkle=pow(max(0.0,m),4.0)*.19;
    } else if(design==7) { // Spectral glass: tightly clipped audio columns
        float cells=max(32.0,round(84.0*density));
        float local=repeatDistance(u*cells);
        float separation=1.0-smoothstep(.30,.47,local);
        float height=(5.0+e*65.0+beat*8.0)*base*spread;
        float column=smoothstep(-height-2.0*base,-height+1.5*base,d)*
            (1.0-smoothstep(-1.0*base,2.0*base,d));
        body=column*separation;
        bloom=gaussian(d+height*.5,height*.65+6.0)*separation*.18;
        sparkle=body*(.18+.30*e);
    } else if(design==8) { // Ripples: concentric travelling interference ripples
        float wave=sin(d/(4.0*base)-t*4.4+e*3.0);
        float ring=pow(max(0.0,wave),9.0);
        float envelope=gaussian(d,24.0*base*(.5+spread));
        body=ring*envelope*(.38+.62*max(e,beat));
        bloom=envelope*.17;
        sparkle=ring*.32*e;
    } else if(design==9) { // Plasma: animated 3D cellular current
        float n=noise3(float3(u*13.0,d*.075,t*.40));
        float m=noise3(float3(u*27.0,d*.15,t*.24+6.0));
        float ions=pow(max(0.0,(n*.72+m*.28)*.5+.5),3.3);
        float envelope=gaussian(d,23.0*base*spread);
        body=ions*envelope*(.58+.42*e);
        bloom=envelope*ions*.21;
        sparkle=pow(ions,3.0)*.35;
    } else if(design==10) { // Shards: faceted angular energy geometry
        float cells=max(20.0,round(34.0*density));
        float cell=floor(u*cells);
        float local=fract(u*cells);
        float skew=hash21(float2(cell,8.0));
        float spike=(1.0-abs(local*2.0-1.0))*(8.0+18.0*e)*base;
        float faceted=abs(d+spike-skew*11.0*base);
        body=(1.0-smoothstep(0.7*base,3.0*base,faceted))*(.4+.6*e);
        bloom=gaussian(faceted,7.0*base)*.20;
        sparkle=body*.48;
    } else { // Orbit beads: concentric rotating dot trains
        float cells=max(18.0,round(36.0*density));
        float mover=fract(u-t*.075);
        float cell=floor(mover*cells);
        float local=repeatDistance(mover*cells)*edge/cells;
        float lane=mod(cell,3.0);
        float radiusLocal=(2.5+beat*3.0+e*2.0)*base*dotSize;
        float y=(lane-1.0)*10.0*base;
        float dis=length(float2(local,d-y));
        body=circleMask(local,d-y,radiusLocal)*(.60+.40*sin(cell*.41+t*2.0));
        bloom=gaussian(dis,radiusLocal*3.4)*.24;
        sparkle=body*.42;
    }
    float a=clamp((body*.82+bloom*(.5+glow)+sparkle*.10)*brightness,0.0,.91);
    if (a<.003) return half4(0.0);
    float3 col=palette(u,t);
    col=mix(col,float3(1.0),clamp(sparkle*.35+body*.15,0.0,.50));
    // Premultiplied output is required by RuntimeShader and avoids black halos
    // around gradient stops in transparent Android overlay windows.
    return half4(half3(clamp(col,0.0,1.0))*half(a),half(a));
}
        """.trimIndent()
    }
}
