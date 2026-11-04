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

/**
 * Single-pass AGSL light materials. Full-screen transparent pixels are discarded.
 * Every design here is GPU-generated and shares the same music/notification input.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ShaderEdgeEngine {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val frequencies = FloatArray(8)
    private val firstHalf = FloatArray(4)
    private val secondHalf = FloatArray(4)
    private var shader: RuntimeShader? = null
    private var disabled = false
    private val zoneNames = arrayOf(
        "top0", "top1", "top2", "top3",
        "right0", "right1", "right2", "right3",
        "bottom0", "bottom1", "bottom2", "bottom3",
        "left0", "left1", "left2", "left3"
    )

    /** Compile the same source used for music, notifications, camera and optional video. */
    internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)

    fun render(canvas: Canvas, width: Int, height: Int, bounds: RectF, radius: Float,
        mode: EdgeMode, bands: FloatArray, bass: Float, phase: Float, seconds: Float,
        thickness: Float, spread: Float, glow: Float, brightness: Float,
        primary: Int, secondary: Int, attack: Float, compact: Boolean = false,
        video: VideoEdgeColors? = null, videoFrame: RectF? = null,
        ambientMotion: Boolean = false, videoStyle: VideoLightStyle = VideoLightStyle.SELECTED,
        videoWidthPx: Float = 30f, videoIntensity: Float = .58f,
        dynamicLow: Float = 0f, dynamicMid: Float = 0f,
        dynamicHigh: Float = 0f, dynamicHit: Float = 0f,
        dynamicForce: Float = 2.4f, pixelDensity: Float = 1f,
        edgePresence: Float = 1f): Boolean {
        if (disabled || !canvas.isHardwareAccelerated || width <= 0 || height <= 0) return false
        return try {
            val runtime = shader ?: RuntimeShader(SOURCE).also { shader = it }
            runtime.setFloatUniform("resolution",width.toFloat(),height.toFloat())
            runtime.setFloatUniform("frame",bounds.left,bounds.top,bounds.right,bounds.bottom)
            runtime.setFloatUniform("radius",radius)
            runtime.setFloatUniform("seconds",seconds)
            runtime.setFloatUniform("phase",phase)
            runtime.setFloatUniform("bass",bass.coerceIn(0f,1f))
            runtime.setFloatUniform("attack",attack.coerceIn(0f,1.5f))
            runtime.setFloatUniform("thickness",thickness)
            runtime.setFloatUniform("spread",spread)
            runtime.setFloatUniform("glow",glow)
            runtime.setFloatUniform("brightness",brightness)
            runtime.setIntUniform("design",mode.shaderId)
            runtime.setFloatUniform("pixelDensity",pixelDensity.coerceIn(.5f, 6f))
            runtime.setFloatUniform("compact", if (compact) 1f else 0f)
            runtime.setFloatUniform("ambientMotion", if (ambientMotion) 1f else 0f)
            runtime.setIntUniform("videoMode", if (video == null) 0 else 1)
            runtime.setIntUniform("videoStyle", videoStyle.ordinal)
            runtime.setFloatUniform("videoWidth", videoWidthPx.coerceIn(4f, 140f))
            runtime.setFloatUniform("videoIntensity", videoIntensity.coerceIn(.12f, 1f))
            runtime.setFloatUniform("musicDynamics",
                dynamicLow.coerceIn(0f, 1f), dynamicMid.coerceIn(0f, 1f),
                dynamicHigh.coerceIn(0f, 1f), dynamicHit.coerceIn(0f, 1.5f))
            runtime.setFloatUniform("dynamicForce", dynamicForce.coerceIn(.6f, 3.5f))
            runtime.setFloatUniform("edgePresence", edgePresence.coerceIn(0f, 1f))
            if (video != null) {
                val sampledFrame = videoFrame ?: bounds
                runtime.setFloatUniform("videoFrame",
                    sampledFrame.left, sampledFrame.top, sampledFrame.right, sampledFrame.bottom)
                for (index in zoneNames.indices) {
                    val fallback = when (index / 4) {
                        0 -> video.top
                        1 -> video.right
                        2 -> video.bottom
                        else -> video.left
                    }
                    val color = video.zones.getOrNull(index) ?: fallback
                    runtime.setFloatUniform(zoneNames[index],
                        Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
                }
            }
            java.util.Arrays.fill(frequencies,0f)
            for (i in bands.indices) {
                val k=(i*8/max(1,bands.size)).coerceIn(0,7)
                frequencies[k]=max(frequencies[k],bands[i].coerceIn(0f,1f))
            }
            System.arraycopy(frequencies, 0, firstHalf, 0, 4)
            System.arraycopy(frequencies, 4, secondHalf, 0, 4)
            runtime.setFloatUniform("audio",firstHalf)
            runtime.setFloatUniform("audioHigh",secondHalf)
            runtime.setFloatUniform("first", Color.red(primary)/255f,
                Color.green(primary)/255f,Color.blue(primary)/255f)
            runtime.setFloatUniform("second",Color.red(secondary)/255f,
                Color.green(secondary)/255f,Color.blue(secondary)/255f)
            paint.shader=runtime
            paint.alpha=255
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader=null
            true
        } catch (failure: RuntimeException) {
            disabled=true
            paint.shader=null
            Log.e("HALO","AGSL renderer unavailable; no obsolete line animation will be substituted",failure)
            false
        }
    }
    private companion object {
        private val SOURCE = """
            
            uniform float2 resolution;
            uniform float4 frame;
            uniform float radius;
            uniform float seconds;
            uniform float phase;
            uniform float bass;
            uniform float attack;
            uniform float4 audio;
            uniform float4 audioHigh;
            uniform float thickness;
            uniform float spread;
            uniform float glow;
            uniform float brightness;
            uniform float3 first;
            uniform float3 second;
            uniform int design;
            uniform float pixelDensity;
            uniform float compact;
            uniform float ambientMotion;
            uniform float4 musicDynamics;
            uniform float dynamicForce;
            uniform float edgePresence;
            // Capture replaces ONLY the colors. Shape, particles, fluid movement, glow
            // and timing remain controlled by the selected animation.
            uniform int videoMode;
            uniform int videoStyle;
            uniform float videoWidth;
            uniform float videoIntensity;
            uniform float4 videoFrame;
            uniform float3 top0;
            uniform float3 top1;
            uniform float3 top2;
            uniform float3 top3;
            uniform float3 right0;
            uniform float3 right1;
            uniform float3 right2;
            uniform float3 right3;
            uniform float3 bottom0;
            uniform float3 bottom1;
            uniform float3 bottom2;
            uniform float3 bottom3;
            uniform float3 left0;
            uniform float3 left1;
            uniform float3 left2;
            uniform float3 left3;
            
            const float PI = 3.14159265358979;
            const float TAU = 6.28318530717958;
            float hash(float2 p) {
                return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453);
            }
            float noise(float2 p) {
                float2 i=floor(p), f=fract(p);
                float2 sm=f*f*(3.0-2.0*f);
                float a=hash(i), b=hash(i+float2(1.0,0.0));
                float c=hash(i+float2(0.0,1.0)), d=hash(i+float2(1.0,1.0));
                return mix(mix(a,b,sm.x),mix(c,d,sm.x),sm.y);
            }
            
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
            
            float roundedDistance(float2 p) {
                float2 c=.5*(frame.xy+frame.zw);
                float2 halfSize=max((frame.zw-frame.xy)*.5,float2(1.0));
                float r=min(radius,min(halfSize.x,halfSize.y));
                float2 q=abs(p-c)-(halfSize-r);
                return length(max(q,float2(0.0))) + min(max(q.x,q.y),0.0)-r;
            }
            
            // Arc-length parameterization of the actual rounded rectangle, NOT atan() from
            // the screen center. Gradient color and individual beads therefore turn every
            // corner smoothly, without diagonal stretching or a color reset at the seam.
            float edgeParameter(float2 p, out float perimeter) {
                float2 c=.5*(frame.xy+frame.zw);
                float2 e=max((frame.zw-frame.xy)*.5,float2(1.0));
                float r=max(1.0,min(radius,min(e.x,e.y)));
                float2 a=max(e-r,float2(.001));
                float h=2.0*a.x, v=2.0*a.y, arc=.5*PI*r;
                perimeter=2.0*(h+v)+4.0*arc;
                float2 q=p-c, b=abs(q);
                float s=0.0;
                if (b.x>a.x && b.y>a.y) {
                    if (q.x>=0.0 && q.y<0.0) {
                        float ang=atan(q.y+a.y,q.x-a.x);
                        s=h+(ang+.5*PI)*r;
                    } else if(q.x>=0.0) {
                        float ang=atan(q.y-a.y,q.x-a.x);
                        s=h+arc+v+ang*r;
                    } else if (q.y>=0.0) {
                        float ang=atan(q.y-a.y,q.x+a.x);
                        s=h+arc+v+arc+h+(ang-.5*PI)*r;
                    } else {
                        float ang=atan(q.y+a.y,q.x+a.x);
                        s=h+arc+v+arc+h+arc+v+(ang+PI)*r;
                    }
                } else if (b.y>=a.y) {
                    if(q.y<0.0) s=q.x+a.x;
                    else s=h+arc+v+arc+(a.x-q.x);
                } else {
                    if(q.x>=0.0) s=h+arc+(q.y+a.y);
                    else s=h+arc+v+arc+h+arc+(a.y-q.y);
                }
                return fract(s/max(perimeter,1.0));
            }
            // All eight spectrum groups contribute; the last interpolates back to the
            // first so music peaks have no jump around the rounded edge seam.
            float harmonic(float u) {
                float x=fract(u)*8.0;
                float k=floor(x), t=fract(x);
                t=t*t*(3.0-2.0*t);
                if(k<1.0) return mix(audio.x,audio.y,t);
                if(k<2.0) return mix(audio.y,audio.z,t);
                if(k<3.0) return mix(audio.z,audio.w,t);
                if(k<4.0) return mix(audio.w,audioHigh.x,t);
                if(k<5.0) return mix(audioHigh.x,audioHigh.y,t);
                if(k<6.0) return mix(audioHigh.y,audioHigh.z,t);
                if(k<7.0) return mix(audioHigh.z,audioHigh.w,t);
                return mix(audioHigh.w,audio.x,t);
            }
            float3 interpolateZones(float3 a, float3 b, float3 c, float3 d, float u) {
                // Frame sampling uses four zones along each physical screen edge,
                // preserving colors under the corresponding content, not equal arc lengths.
                // Samples are centered at 1/8, 3/8, 5/8 and 7/8 of the side.
                // The previous 20%-80% mapping misaligned colors by ~12% of
                // the screen, especially conspicuous during moving scenes.
                float position=clamp((u-.125)/.25,0.0,3.0);
                if(position<1.0) return mix(a,b,position);
                if(position<2.0) return mix(b,c,position-1.0);
                return mix(c,d,position-2.0);
            }
            float3 sampledScreenColor(float2 p) {
                float2 extent=max(videoFrame.zw-videoFrame.xy,float2(1.0));
                float x=clamp((p.x-videoFrame.x)/extent.x,0.0,1.0);
                float y=clamp((p.y-videoFrame.y)/extent.y,0.0,1.0);
                float dl=abs(p.x-videoFrame.x);
                float dr=abs(p.x-videoFrame.z);
                float dt=abs(p.y-videoFrame.y);
                float db=abs(p.y-videoFrame.w);
                // The closest physical edge dominates. Corner colors blend smoothly.
                float weight=36.0;
                // Normalize by the nearest side so camera rings work even
                // when they sit far inside the screen's global bounds.
                float nearest=min(min(dl,dr),min(dt,db));
                float sl=exp(-max(dl-nearest,0.0)/weight);
                float sr=exp(-max(dr-nearest,0.0)/weight);
                float st=exp(-max(dt-nearest,0.0)/weight);
                float sb=exp(-max(db-nearest,0.0)/weight);
                float3 t=interpolateZones(top0,top1,top2,top3,x);
                float3 r=interpolateZones(right0,right1,right2,right3,y);
                float3 b=interpolateZones(bottom0,bottom1,bottom2,bottom3,1.0-x);
                float3 l=interpolateZones(left0,left1,left2,left3,1.0-y);
                return (st*t+sr*r+sb*b+sl*l)/max(st+sr+sb+sl,.00001);
            }
            float periodicDistance(float u, float point) {
                return abs(fract(u-point+.5)-.5);
            }
            float gaussian(float x, float width) {
                float z=x/max(width,.0001);
                return exp(-2.0*z*z);
            }
            // Analytic capsules and discs, in physical pixels. These materials have
            // their own tightly bounded bloom and never use the electric noise field.
            half4 minimalLight(float2 px, float d, float u, float perimeter) {
                float unit=max(pixelDensity,.5);
                float line=clamp(thickness,.6*unit,3.0*unit);
                float reach=clamp(spread,.3,2.8);
                float low=clamp(musicDynamics.x*dynamicForce*.65,0.0,1.0);
                float mid=clamp(musicDynamics.y*dynamicForce*.65,0.0,1.0);
                float high=clamp(musicDynamics.z*dynamicForce*.65,0.0,1.0);
                float hit=clamp((musicDynamics.w+attack*.25)*dynamicForce*.55,0.0,1.0);
                float level=clamp(.56*low+.27*mid+.12*high+.94*hit,0.0,1.0);
                float gain=level;
                // A visible, gentle no-capture mode remains explicitly distinct from
                // beat response. No timer-generated beats enter the music envelopes.
                if(ambientMotion>.5 || videoMode==1) {
                    float breath=.5-.5*cos(seconds*.9);
                    gain=max(gain,.28+.06*breath);
                    low=max(low,.10+.05*breath);
                }
                if(gain<.001) return half4(0.0);
                float limit=min(24.0*unit,max(14.0*unit,line*10.0));
                if(design>=47 && design<=58) limit=29.0*unit;
                if(abs(d)>limit) return half4(0.0);
                float2 e=max((frame.zw-frame.xy)*.5,float2(1.0));
                float r=clamp(radius,1.0,min(e.x,e.y));
                float h=max(2.0*(e.x-r),.001);
                float v=max(2.0*(e.y-r),.001);
                float arc=.5*PI*r;
                float shapeDistance=0.0;
                float weight=1.0;
                float tint=.5-.5*cos(TAU*u);
                float highlight=0.0;
                if(design>=47 && design<=58) {
                    // Motion families informed by real edge-lighting references:
                    // serial bulbs, discrete bars, traveling light, wave contours
                    // and sparse localized particle drift. No whole-screen masks.
                    float t=seconds;
                    float drive=clamp(.45+.55*low+.35*mid+.50*hit,.35,1.5);
                    float w=max(perimeter,1.0);
                    float nearLeft=abs(px.x-frame.x);
                    float nearRight=abs(px.x-frame.z);
                    float nearSide=min(nearLeft,nearRight);
                    float sideGate=1.0-smoothstep(4.0*unit,24.0*unit,nearSide);
                    float nearTop=abs(px.y-frame.y);
                    float nearBottom=abs(px.y-frame.w);
                    float horizontalGate=1.0-smoothstep(4.0*unit,22.0*unit,
                        min(nearTop,nearBottom));
                    float yNorm=clamp((px.y-frame.y)/max(frame.w-frame.y,1.0),0.0,1.0);
                    float xNorm=clamp((px.x-frame.x)/max(frame.z-frame.x,1.0),0.0,1.0);
                    if(design==47) {
                        // Serial Lights: literal traveling round bulbs.
                        float count=compact>.5 ? 17.0 : clamp(floor(w/(14.0*unit)),25.0,210.0);
                        float shift=t*(1.1+.9*low);
                        float lane=u*count-shift;
                        float index=floor(lane);
                        float across=(fract(lane)-.5)*w/count;
                        float position=fract((index+.5+shift)/count);
                        float local=harmonic(position);
                        float pulse=pow(.5+.5*cos(TAU*(position-t*.105)),5.0);
                        float rad=unit*(.70+.20*local+.32*high);
                        shapeDistance=length(float2(across,d))-rad;
                        weight=.25+.44*local+.53*pulse+.13*hit;
                        highlight=.20*pulse;
                        tint=.5-.5*cos(TAU*(u-t*.025));
                    } else if(design==48 || design==49) {
                        // Physically drawn perimeter bars (rounded boxes), not
                        // a sine-wave alpha trick on an ordinary outline.
                        float count=compact>.5 ? 25.0 : clamp(floor(w/(8.0*unit)),45.0,260.0);
                        float lane=u*count;
                        float at=(floor(lane)+.5)/count;
                        float local=clamp(harmonic(at)*1.8,0.0,1.0);
                        float barHeight=unit*(1.4+(design==48 ? 10.0 : 14.0)*local
                            +3.0*hit+(design==49 ? 2.0*mid : 0.0));
                        float halfWidth=max(.50*unit,w/count*.21);
                        float along=(fract(lane)-.5)*w/count;
                        float2 q=abs(float2(along,d+barHeight*.46))
                            -float2(halfWidth,barHeight*.50);
                        shapeDistance=length(max(q,float2(0.0)))+
                            min(max(q.x,q.y),0.0)-.25*unit;
                        float tick=.68+.18*sin(t*.28+at*TAU*4.0);
                        weight=(.29+.34*local+.33*hit)*tick;
                        if(design==49) {
                            // Side Bars only: sides are active, top/bottom stay empty.
                            weight*=sideGate;
                        }
                        highlight=.06*high;
                    } else if(design==50 || design==51 || design==52) {
                        // Continuous signed-distance wave contours, including
                        // waves that pass through the rounded corners.
                        float local=clamp(harmonic(u)*1.4,0.0,1.0);
                        float wave0=sin(TAU*(u*(design==51 ? 8.0 : 5.0)-t*.17));
                        float wave1=sin(TAU*(u*13.0+t*.095)+mid*1.7);
                        float shift=unit*(.7+5.8*low+2.8*local+2.2*hit)*
                            (wave0*.74+wave1*.26);
                        float layerDistance=abs(d-shift)-line*.49;
                        if(design==50) {
                            float waveB=sin(TAU*(u*8.0+t*.095));
                            float second=abs(d-(shift+waveB*2.6*unit))-line*.27;
                            shapeDistance=min(layerDistance,second);
                            weight=sideGate*(.41+.27*local+.31*low+.18*hit);
                        } else if(design==51) {
                            shapeDistance=layerDistance;
                            weight=.41+.31*local+.25*low+.25*hit;
                        } else {
                            float extra=abs(d-shift-2.8*unit)-line*.26;
                            float third=abs(d-shift+2.8*unit)-line*.22;
                            shapeDistance=min(layerDistance,min(extra,third));
                            weight=horizontalGate*(.40+.36*local+.23*low+.22*hit);
                        }
                        highlight=.06*high;
                        tint=.5-.5*cos(TAU*(u-t*.03));
                    } else if(design==53) {
                        // Chase Light: one traveling elongated comet with a
                        // thin residual border, no detached bright particles.
                        float head=fract(t*.087);
                        float gap=periodicDistance(u,head)*w;
                        float trail=gaussian(gap,55.0*unit);
                        float core=gaussian(gap,12.0*unit);
                        shapeDistance=abs(d)-line*(.42+.40*core);
                        weight=.10+(.58+.34*low+.37*hit)*trail;
                        highlight=.40*core;
                        tint=.5-.5*cos(TAU*(u-t*.035));
                    } else if(design==54 || design==55) {
                        // A deterministic field of distinct dots, not noise
                        // painted as a static line. Only a small edge strip.
                        float count=compact>.5 ? 24.0 : clamp(floor(w/(9.0*unit)),30.0,300.0);
                        float motion=t*(design==54 ? .33 : .73);
                        float lane=u*count-motion;
                        float cell=floor(lane);
                        float widthPx=w/count;
                        float across=(fract(lane)-.5)*widthPx;
                        float rand=hash(float2(cell,9.0));
                        float rand2=hash(float2(cell,37.0));
                        float offset=(rand-.5)*unit*(design==54 ? 12.0 : 10.0);
                        float dot=unit*(.35+rand2*.85)*(1.0+.24*high);
                        shapeDistance=length(float2(across,d-offset))-dot;
                        if(design==54) {
                            float corner0=(h+arc*.5)/w;
                            float corner1=(h+v+arc*1.5)/w;
                            float corner2=(2.0*h+v+arc*2.5)/w;
                            float corner3=(2.0*h+2.0*v+arc*3.5)/w;
                            float cornerNear=min(min(periodicDistance(u,corner0),
                                periodicDistance(u,corner1)),
                                min(periodicDistance(u,corner2),
                                    periodicDistance(u,corner3)))*w;
                            float cornerMask=1.0-smoothstep(18.0*unit,92.0*unit,
                                cornerNear);
                            weight=cornerMask*(.25+.53*rand2+.30*hit);
                        } else {
                            float flutter=.70+.30*sin(TAU*(u*9.0-t*.13));
                            weight=flutter*(.21+.35*rand2+.23*mid+.24*hit);
                        }
                        highlight=.10*rand2;
                    } else if(design==56) {
                        // Smooth side-lights with a small luminous core and a
                        // controlled haze: no duplicate overlay window.
                        float y=sin(PI*yNorm);
                        shapeDistance=abs(d)-unit*(1.6+1.6*low+1.0*hit);
                        float breathing=.5+.5*sin(t*.95);
                        weight=sideGate*(.34+.44*y+.17*breathing+.19*low);
                        highlight=.1*breathing;
                    } else if(design==57) {
                        // Flat Flutter: restrained intermittent small strips
                        // along the edge, tied to the measured bass envelope.
                        float count=compact>.5 ? 12.0 : clamp(floor(w/(19.0*unit)),28.0,110.0);
                        float at=(floor(u*count)+.5)/count;
                        float along=(fract(u*count)-.5)*w/count;
                        float pulse=sin(TAU*(at*4.0-t*.13));
                        float lengthBar=unit*(1.2+3.5*low+2.2*mid
                            +2.5*max(pulse,0.0)*hit);
                        shapeDistance=length(float2(max(abs(along)-w/count*.21,0.0),
                            d+lengthBar*.2))-line*(.45+.11*high);
                        weight=.34+.31*low+.30*hit+.13*max(pulse,0.0);
                        highlight=.08*high;
                    } else {
                        // Fire Lines: coherent traveling flames on the two
                        // sides only. Smooth band-limited noise avoids flicker.
                        float flame=noise(float2(u*37.0-t*.71,d/(3.0*unit)));
                        float ridge=sin(TAU*(u*16.0+t*.13));
                        float offset=unit*(1.5+6.4*low+4.2*hit)*
                            (flame*.65+.35*ridge);
                        shapeDistance=abs(d+offset)-line*(.47+.33*mid);
                        weight=sideGate*(.25+.47*flame+.38*low+.29*hit);
                        highlight=.13*high;
                        tint=.25+.25*sin(TAU*(u*1.8-t*.065));
                    }
                } else if(design>=39 && design<=46) {
                    // A separate, genuinely moving family. Geometry responds
                    // to measured FFT bass, mids, highs and real transients.
                    float kick=clamp(hit*.95+low*.36,0.0,1.3);
                    float t=seconds;
                    float drive=.32+.70*low+.45*mid+1.15*hit;
                    if(design==39) {
                        // A continuous border shifts inward as bass pressure rises.
                        float offset=(.5+3.3*low+1.7*hit)*unit;
                        shapeDistance=abs(d+offset)-line*(.62+.42*kick);
                        weight=.34+.33*low+.42*hit;
                        highlight=.14*hit;
                    } else if(design==40) {
                        // Mirrored side waves, not a random glowing rectangle.
                        float right=(h+arc+v*.5)/perimeter;
                        float left=(2.0*h+3.0*arc+1.5*v)/perimeter;
                        float along=min(periodicDistance(u,right),
                            periodicDistance(u,left))*perimeter;
                        float lengthSide=v*(.11+.34*low+.18*hit);
                        float bend=sin(along/max(11.0*unit,1.0)*2.0
                            -t*1.3)*unit*(.8+2.1*low+.8*hit);
                        shapeDistance=length(float2(max(along-lengthSide,0.0),
                            d-bend))-line*(.80+.35*kick);
                        weight=.31+.35*low+.45*hit;
                        highlight=.12*high;
                    } else if(design==41) {
                        // Three separate traveling perimeter highlights.
                        float head=fract(t*.062*drive);
                        float near0=periodicDistance(u,head)*perimeter;
                        float near1=periodicDistance(u,fract(head+.33))*perimeter;
                        float near2=periodicDistance(u,fract(head+.66))*perimeter;
                        float trail=max(gaussian(near0,26.0*unit),
                            max(gaussian(near1,20.0*unit),
                                gaussian(near2,16.0*unit)));
                        shapeDistance=abs(d)-line*(.58+.18*kick);
                        weight=.14+(.31+.55*kick)*trail;
                        highlight=.30*trail;
                        tint=fract(u*.85+t*.015);
                    } else if(design==42) {
                        // A bass onset illuminates an outward-moving band
                        // measured along both directions from the lower edge.
                        float bottom=(1.5*h+2.0*arc+v)/perimeter;
                        float along=periodicDistance(u,bottom)*perimeter;
                        float travel=fract(t*.17*drive)*(.5*perimeter);
                        float pulse=gaussian(along-travel,18.0*unit);
                        shapeDistance=abs(d)-line*(.50+.33*hit);
                        weight=.15+.32*low+.57*pulse*hit;
                        highlight=.39*pulse*hit;
                    } else if(design==43) {
                        // Rounded corner brackets extend only on measured beats.
                        float c0=(h+arc*.5)/perimeter;
                        float c1=(h+v+arc*1.5)/perimeter;
                        float c2=(2.0*h+v+arc*2.5)/perimeter;
                        float c3=(2.0*h+2.0*v+arc*3.5)/perimeter;
                        float along=min(min(periodicDistance(u,c0),
                            periodicDistance(u,c1)),
                            min(periodicDistance(u,c2),periodicDistance(u,c3)))
                            *perimeter;
                        float lengthCorner=arc*.25+min(h,v)*(.02+.21*low+.12*hit);
                        shapeDistance=length(float2(max(along-lengthCorner,0.0),d))
                            -line*(.74+.27*hit);
                        weight=.29+.32*low+.47*hit;
                        highlight=.22*hit;
                    } else if(design==44) {
                        // Eight local FFT groups visibly deform the line.
                        float local=harmonic(u);
                        float wave=sin(TAU*(u*12.0-t*.075));
                        float offset=wave*unit*(.65+4.3*local+1.9*hit);
                        shapeDistance=abs(d-offset)-line*(.53+.15*local);
                        weight=.27+.44*local+.16*high+.28*hit;
                        highlight=.08*high;
                    } else if(design==45) {
                        // Smooth multi-harmonic flow, no orbiting particles.
                        float bend=(sin(TAU*(u*4.0-t*.085))*.61+
                            sin(TAU*(u*9.0+t*.063))*.26+
                            sin(TAU*(u*16.0-t*.041))*.13)
                            *unit*(.5+4.4*low+1.8*hit);
                        shapeDistance=abs(d-bend)-line*(.70+.16*mid);
                        weight=.34+.25*low+.28*mid+.30*hit;
                        highlight=.07*high;
                        tint=.5-.5*cos(TAU*(u-t*.019));
                    } else {
                        // Two bouncing side capsules, bass length & treble tips.
                        float right=(h+arc+v*.5)/perimeter;
                        float left=(2.0*h+3.0*arc+1.5*v)/perimeter;
                        float along=min(periodicDistance(u,right),
                            periodicDistance(u,left))*perimeter;
                        float halfLength=v*(.04+.33*low+.18*hit);
                        float bend=unit*sin(along/max(8.0*unit,1.0)
                            -t*.73)*(1.0+1.4*mid);
                        shapeDistance=length(float2(max(along-halfLength,0.0),
                            d-bend))-line*(.72+.38*high);
                        weight=.21+.30*low+.30*mid+.40*hit;
                        highlight=gaussian(along-halfLength,
                            max(2.0*unit,line*3.0))*(.28*high+.19*hit);
                    }
                } else if(design>=9 && design<=38) {
                    // Thirty restrained edge materials: one shader, grouped
                    // into real geometric families rather than random particles.
                    // Every variant stays within the existing 20dp edge envelope.
                    int style=design-9;
                    int family=style/6;
                    int variant=style-family*6;
                    float baseLine=line*.55;
                    float sheen=0.0;
                    float wave=.5+.5*sin(seconds*.82);
                    shapeDistance=abs(d)-baseLine;
                    weight=.58+.19*low+.08*hit;
                    highlight=0.02*high;
                    tint=.5-.5*cos(TAU*u);
                    if(family==0) {
                        // Six complete, elegant outlines.
                        if(variant==0) {
                            shapeDistance=abs(d)-line*.38;
                            weight=.63+.14*low;
                        } else if(variant==1) {
                            shapeDistance=min(abs(d-line*.85),abs(d+line*.85))
                                -line*.33;
                            weight=.48+.17*low;
                        } else if(variant==2) {
                            shapeDistance=abs(d+line*.70)-line*.43;
                            weight=.62+.12*low;
                        } else if(variant==3) {
                            shapeDistance=abs(d-line*.70)-line*.43;
                            weight=.61+.12*low;
                        } else if(variant==4) {
                            float corners=.5+.5*cos(TAU*u*4.0);
                            weight=.42+.31*pow(corners,3.0)+.14*low;
                            shapeDistance=abs(d)-line*.48;
                        } else {
                            shapeDistance=abs(d)-line*.39;
                            weight=.50+.08*wave+.20*low;
                            highlight=.09+.055*high;
                        }
                    } else if(family==1) {
                        // Six soft low-profile glows; no wide blurry fields.
                        if(variant==0) {
                            shapeDistance=abs(d)-line*.56;
                            weight=.47+.08*wave+.18*low;
                        } else if(variant==1) {
                            shapeDistance=abs(d)-line*(.60+.08*low);
                            weight=.36+.22*wave+.24*low+.06*hit;
                        } else if(variant==2) {
                            shapeDistance=abs(d)-line*(.50+.07*wave);
                            weight=.41+.23*wave+.24*low;
                        } else if(variant==3) {
                            shapeDistance=min(abs(d-line*.65),abs(d+line*.65))
                                -line*.42;
                            weight=.42+.14*low+.08*wave;
                        } else if(variant==4) {
                            shapeDistance=abs(d)-line*.50;
                            tint=.23+.16*sin(TAU*u-seconds*.13);
                            weight=.46+.18*low+.10*wave;
                        } else {
                            shapeDistance=abs(d)-line*.45;
                            highlight=.12+.045*wave+.06*high;
                            weight=.48+.08*wave+.15*low;
                        }
                    } else if(family==2) {
                        // Six true music accents. Kicks and bands are measured
                        // from Android's FFT, never from a fake metronome.
                        if(variant==0) {
                            shapeDistance=abs(d)-line*(.28+.64*low+.20*hit);
                            weight=.21+.69*low+.22*hit;
                        } else if(variant==1) {
                            shapeDistance=abs(d)-line*(.77-.33*hit);
                            weight=.30+.26*low+.58*hit;
                        } else if(variant==2) {
                            shapeDistance=abs(d)-line*.48;
                            highlight=.58*hit;
                            weight=.25+.76*hit+.14*low;
                        } else if(variant==3) {
                            shapeDistance=abs(d)-line*(.47+.09*mid);
                            weight=.22+.71*mid+.16*low;
                        } else if(variant==4) {
                            sheen=pow(.5+.5*cos(TAU*(u*6.0-seconds*.024)),10.0);
                            shapeDistance=abs(d)-line*.43;
                            highlight=.20*high*sheen;
                            weight=.29+.58*high*sheen+.17*low;
                        } else {
                            shapeDistance=abs(d)-line*.50;
                            float spectrum=harmonic(u);
                            weight=.28+.20*low+.53*spectrum+.09*hit;
                            tint=.25+.50*clamp(spectrum,.0,1.0);
                        }
                    } else if(family==3) {
                        // Six extremely subtle moving accents, always attached
                        // to a continuous border; never a roaming bright blob.
                        shapeDistance=abs(d)-line*.48;
                        float head=fract(seconds*(.072+.020*low));
                        float along=0.0;
                        if(variant==0) {
                            along=periodicDistance(u,head)*perimeter;
                            sheen=gaussian(along,38.0*unit);
                        } else if(variant==1) {
                            along=min(periodicDistance(u,head),
                                periodicDistance(u,1.0-head))*perimeter;
                            sheen=gaussian(along,28.0*unit);
                        } else if(variant==2) {
                            float corner=abs(sin(TAU*(u*2.0-seconds*.095)));
                            sheen=pow(corner,12.0)*.72;
                        } else if(variant==3) {
                            float yy=clamp((px.y-frame.y)/
                                max(1.0,frame.w-frame.y),0.0,1.0);
                            sheen=gaussian(yy-fract(seconds*.17),.14);
                        } else if(variant==4) {
                            sheen=.5+.5*sin(TAU*(u*5.0-seconds*.13));
                            shapeDistance=abs(d-unit*sheen*(.35+1.2*low))
                                -line*(.40+.16*sheen);
                        } else {
                            along=periodicDistance(u,head)*perimeter;
                            sheen=gaussian(along,74.0*unit);
                        }
                        weight=.29+.16*low+.49*sheen+.17*hit;
                        highlight=.30*sheen+.08*high;
                    } else {
                        // Fine micro-patterns. Dashes / beads stay genuinely
                        // small even at phone resolution and around camera cutouts.
                        float spacing=compact>.5 ? 5.5*unit : 11.0*unit;
                        float count=clamp(floor(perimeter/spacing),8.0,400.0);
                        // Slowly traveling microtextures with no hard seam.
                        float cursor=fract(u*count-seconds*(.20+.16*low));
                        float cell=abs(cursor-.5);
                        float across=(cursor-.5)*perimeter/count;
                        if(variant==0) {
                            shapeDistance=length(float2(across,d))-line*(.68+.25*low);
                            weight=.46+.26*low+.13*hit;
                        } else if(variant==1) {
                            shapeDistance=abs(d)-line*.43;
                            weight=(1.0-smoothstep(.19,.34,cell))*
                                (.48+.31*low+.10*hit);
                        } else if(variant==2) {
                            shapeDistance=abs(d)-line*.41;
                            weight=(.32+.68*(1.0-smoothstep(.06,.18,cell)))*
                                (.47+.16*low);
                        } else if(variant==3) {
                            float grain=.5+.5*sin(TAU*u*count*1.15-seconds*.16);
                            shapeDistance=abs(d)-line*.46;
                            weight=.35+.24*grain+.13*low;
                        } else if(variant==4) {
                            shapeDistance=min(abs(d-line*.30),abs(d+line*.30))
                                -line*.24;
                            highlight=.08+.06*high;
                            weight=.43+.12*low;
                        } else {
                            shapeDistance=length(float2(across,d))-line*(.75+.28*low);
                            weight=.42+.23*wave+.32*low+.12*hit;
                            highlight=.085*high;
                        }
                    }
                } else if(design==6) {
                    // Classic Outline: one unbroken hairline around the full
                    // rounded rectangle. Beats cause only a small brightness
                    // lift, without changing the geometry or spawning particles.
                    shapeDistance=abs(d)-line*(.52+.08*low+.05*hit);
                    weight=.70+.19*low+.09*hit;
                    highlight=.035*hit;
                    tint=.5-.5*cos(TAU*u);
                } else if(design==7) {
                    // Soft Glow: still a single solid border. A quiet halo
                    // breathes cosmetically; real bass adds a tiny expansion.
                    float breathing=.5+.5*sin(seconds*.88);
                    shapeDistance=abs(d)-line*(.53+.14*breathing+.17*low);
                    weight=.43+.22*breathing+.26*low+.12*hit;
                    highlight=.04*breathing+.06*hit;
                    tint=.5-.5*cos(TAU*u);
                } else if(design==8) {
                    // Accent Sweep: single perimeter stroke and a very small
                    // moving sheen, not a separate light orb or spark shower.
                    float head=fract(seconds*.095);
                    float along=periodicDistance(u,head)*perimeter;
                    float sheen=gaussian(along,max(35.0*unit,line*13.0));
                    shapeDistance=abs(d)-line*(.52+.055*low);
                    weight=.41+.17*low+.12*hit+.51*sheen;
                    highlight=.32*sheen+.09*hit;
                    tint=.5-.5*cos(TAU*u);
                } else if(design==1) {
                    // Two anchored capsules. Low frequencies change their length,
                    // mids lift their body, and transients give the tips a quick lift.
                    float right=(h+arc+v*.5)/perimeter;
                    float left=(2.0*h+3.0*arc+1.5*v)/perimeter;
                    float along=min(periodicDistance(u,right),periodicDistance(u,left))*perimeter;
                    float side=max(v,arc*1.6);
                    float extension=clamp((.035+.28*low+.065*hit)*reach,.025,.39);
                    float halfLength=side*extension;
                    shapeDistance=length(float2(max(along-halfLength,0.0),d))-line;
                    highlight=gaussian(along-halfLength,max(line*2.5,unit*2.0))*hit;
                    weight=.72+.28*mid;
                } else if(design==2) {
                    // Fixed, evenly spaced beads; only their size and luminance
                    // breathe. Integer count closes the seam at every aspect ratio.
                    float count=compact>.5 ? 12.0 : clamp(floor(perimeter/(16.0*unit)),24.0,280.0);
                    float moved=u*count-seconds*(.55+.33*low);
                    float cell=floor(moved);
                    float beadU=fract((cell+.5+seconds*(.55+.33*low))/count);
                    float along=(fract(moved)-.5)*perimeter/count;
                    float local=clamp(harmonic(beadU)*dynamicForce*.6,0.0,1.0);
                    float rad=min(perimeter/count*.23,line*(.9+.55*local+.35*high));
                    shapeDistance=length(float2(along,d))-rad;
                    float sheen=pow(.5+.5*cos(TAU*(beadU-seconds*.10)),6.0);
                    weight=.28+.46*local+.36*sheen+.10*hit;
                    highlight=sheen*high*.29;
                    tint=.5-.5*cos(TAU*beadU);
                } else if(design==4) {
                    // Bass Ribbon: two slim mirrored blades running along the
                    // sides. Their length is driven by low-frequency energy.
                    float right=(h+arc+v*.5)/perimeter;
                    float left=(2.0*h+3.0*arc+1.5*v)/perimeter;
                    float along=min(periodicDistance(u,right),
                        periodicDistance(u,left))*perimeter;
                    float halfLength=v*clamp((.07+.34*low+.18*hit)*reach,.045,.55);
                    float breathing=sin(along/max(12.0*unit,1.0)*2.8
                        -seconds*1.2)*unit*(.4+2.3*mid+1.2*hit);
                    float blade=line*(.68+.55*low+.42*hit);
                    shapeDistance=length(float2(max(along-halfLength,0.0),
                        d-breathing))-blade;
                    highlight=gaussian(along-halfLength,max(2.0*unit,blade*3.2))*
                        (.46*hit+.18*high);
                    weight=.52+.24*low+.38*hit;
                    tint=.5-.5*cos(TAU*(u+seconds*.018));
                } else if(design==5) {
                    // Kick Rail: one precise strip anchored to the lower edge.
                    // Its endpoints punch outward on each real percussive hit.
                    float bottom=(1.5*h+2.0*arc+v)/perimeter;
                    float along=periodicDistance(u,bottom)*perimeter;
                    float halfLength=h*clamp((.045+.25*low+.16*hit)*reach,
                        .025,.42);
                    float rail=line*(.66+.58*hit+.23*low);
                    shapeDistance=length(float2(max(along-halfLength,0.0),d))
                        -rail;
                    highlight=gaussian(along-halfLength,max(2.5*unit,rail*3.5))
                        *(.83*hit+.12*high);
                    weight=.42+.28*low+.51*hit;
                    tint=.5-.5*cos(TAU*(u+seconds*.008));
                } else {
                    // Four corner brackets, anchored to the actual rounded arcs.
                    // No orbit or random deformation: the straight ends grow on bass.
                    float a=(h+arc*.5)/perimeter;
                    float b=(h+v+arc*1.5)/perimeter;
                    float c=(2.0*h+v+arc*2.5)/perimeter;
                    float f=(2.0*h+2.0*v+arc*3.5)/perimeter;
                    float along=min(min(periodicDistance(u,a),periodicDistance(u,b)),
                        min(periodicDistance(u,c),periodicDistance(u,f)))*perimeter;
                    float straight=min(h,v);
                    float halfLength=arc*.5+straight*clamp((.025+.14*low+.04*hit)*reach,.012,.26);
                    // Around a circular punch-hole keep four distinct arc accents.
                    if(compact>.5) halfLength=perimeter*(.035+.025*low+.012*hit);
                    shapeDistance=length(float2(max(along-halfLength,0.0),d))-line;
                    highlight=gaussian(along-halfLength,max(line*2.0,unit*2.0))*hit*.65;
                    weight=.74+.26*mid;
                }
                float coverage=1.0-smoothstep(-.65,.85,shapeDistance);
                float bloom=gaussian(max(shapeDistance,0.0),line*2.0+unit*1.5);
                float classic=(design>=6 && design<=38) ? 1.0 : 0.0;
                // A stable readable core plus a small halo. The everyday styles
                // remain restrained; waves and particles have distinct geometry.
                float haloWeight=mix(.16,.09,classic);
                float coreWeight=mix(.82,.86,classic);
                float punch=clamp(.70+.28*low+.34*hit,0.0,1.30);
                float alpha=(coverage*coreWeight+bloom*haloWeight*glow)*
                    weight*brightness*gain*punch*edgePresence;
                alpha*=1.0-smoothstep(limit*.65,limit,abs(d));
                float3 base=mix(first,second,tint);
                if(videoMode==1) {
                    base=sampledScreenColor(px);
                    alpha*=videoIntensity*.72*exp(-max(-d,0.0)/max(videoWidth,1.0)*1.5);
                    alpha*=1.0-smoothstep(1.0,5.0,max(d,0.0));
                    alpha*=smoothstep(.008,.085,max(base.r,max(base.g,base.b)));
                } else {
                    base=mix(base,float3(1.0),clamp(highlight*.22,0.0,.18));
                }
                alpha=clamp(alpha,0.0,.9);
                return half4(half3(base*alpha),half(alpha));
            }
            float disc(float u,float edgeD,float count,float travel,float radial,float rad,float lengthPx) {
                float lane=u*count-travel;
                float cell=floor(lane);
                float across=(fract(lane)-.5)*lengthPx/count;
                float jitter=(hash(float2(cell,4.0))-.5)*rad*.45;
                float dy=edgeD-radial-jitter;
                return gaussian(length(float2(across,dy)),rad);
            }
            
            half4 main(float2 px) {
                float d=roundedDistance(px);
                float thicknessPx=max(.8,thickness);
                if(videoMode==1 && videoStyle!=2) {
                    // Cinema is a genuine edge treatment, never a giant light
                    // blob, orbiting particle or changing white sparkle.
                    if(d < -videoWidth*3.2 || d > 5.0) return half4(0.0);
                    float3 scene=sampledScreenColor(px);
                    float inside=max(-d,0.0);
                    float outside=max(d,0.0);
                    float core=exp(-inside/max(videoWidth*.32,1.0));
                    float soft=exp(-inside/max(videoWidth*.78,1.0));
                    float alpha=(.23*core+.57*soft)*videoIntensity;
                    alpha*=1.0-smoothstep(1.0,5.0,outside);
                    // Very dark video stays dark: never turn black into
                    // fabricated white or make video controls glow.
                    float light=max(scene.r,max(scene.g,scene.b));
                    alpha*=smoothstep(.008,.085,light);
                    if(videoStyle==1) {
                        // A slow 5% breathing variation, not random line noise.
                        alpha*=.95+.05*sin(seconds*1.2);
                    }
                    alpha=clamp(alpha,0.0,.75);
                    return half4(half3(scene*alpha),half(alpha));
                }
                // The minimal materials have a strict 20-density-pixel envelope.
                // Cull before expensive perimeter mapping, harmonic lookup and SDF
                // evaluation. This cannot clip visible pixels: minimalLight() uses
                // an equal or narrower limit. Keep Electric Arcs' original field.
                if (design!=0 && abs(d)>(
                    design>=47 ? 29.0 : 24.0)*max(pixelDensity,.5)) return half4(0.0);
                // Background is genuinely transparent and needs no effect processing.
                float effectExtent=105.0;
                if(abs(d)>max(48.0,thicknessPx*spread*effectExtent)) return half4(0.0);
            
                float lengthPx=1.0;
                float u=edgeParameter(px,lengthPx);
                if(design!=0) return minimalLight(px,d,u,lengthPx);
                // Each frequency region changes a different part of the material.
                // These levels are fed by FFT and spectral flux, never a timer.
                float low=clamp(musicDynamics.x*dynamicForce*.9,0.0,2.2);
                float mids=clamp(musicDynamics.y*dynamicForce*.9,0.0,2.2);
                float highs=clamp(musicDynamics.z*dynamicForce*.9,0.0,2.2);
                float impact=clamp(musicDynamics.w*dynamicForce,0.0,2.5);
                float energy=clamp(harmonic(u),0.0,1.0);
                float kick=clamp(bass+attack*.55+impact*.65,0.0,2.4);
                float time=seconds*.85+phase*.25;
                float volume=clamp((.18+.62*energy+.56*low+.41*mids+
                    .26*highs+.58*impact)*max(spread,.25),.12,3.2);
                float core=0.0, aura=0.0, sparkle=0.0;
                float travel=time*.45;
                float local=0.0;
                // A punch-hole has a much shorter perimeter than the display.
                float densityScale=mix(1.0,.20,compact);
            
                // Restore the exact Electric Arcs geometry the user preferred.
                // Its measured low/mid/high/onset envelopes still control the
                // overall motion and brightness, but do not overwrite its shape.
                float cell=fract(u*(7.0*densityScale+2.0)-time*.39);
                float fracture=sin(u*340.0+time*18.0)*sin(u*171.0-time*11.0);
                float path=d+fracture*5.5*thicknessPx;
                float gate=pow(max(0.0,sin(TAU*(cell-.12))),5.0);
                core=gaussian(path,1.45*thicknessPx)*(gate*.9+.18+kick*.6);
                aura=gaussian(path,11.0*thicknessPx)*(gate*.30+.12*kick);
                sparkle=pow(gate,2.0)*gaussian(path,1.4*thicknessPx);

                // Fix v0.13's hard angular gradient jumps: colors use physical perimeter
                // distance with cosine interpolation, periodic at exactly u=0 and u=1.
                float chroma=.5-.5*cos(TAU*(u-time*.035+
                    .045*mids-.035*highs));
                float3 base=mix(first,second,chroma);
                if(videoMode==1) base=sampledScreenColor(px);
                float whiteSpark=clamp(sparkle*
                    (.12+.46*highs+.28*impact+.17*energy),0.0,.62);
                float3 rgb=videoMode==1 ? base : mix(base,float3(1.0),whiteSpark);
                // Glow is an integral part of the material instead of a second Canvas layer.
                // A pronounced attack response, not an always-bright idle loop.
                float transient=clamp(attack,0.0,1.5);
                float musicalGain=clamp(.12+.42*energy+.34*low+.43*mids+
                    .25*highs+.60*impact+.28*transient,.10,1.7);
                // In Video Mode the motion is scene-driven, not muted by absence of
                // Android audio capture. Keep the selected material animated.
                if(videoMode==1) musicalGain=max(.66,musicalGain);
                if(ambientMotion>0.5) musicalGain=max(.72,musicalGain);
                // More reactive geometry, but without filling unrelated screen
                // pixels. Dynamics control the existing material, not random FX.
                float coreResponse=clamp(.65+.23*low+.30*mids+.17*highs+
                    .40*impact,.45,2.1);
                float auraResponse=clamp(.45+.25*low+.10*mids+.15*impact,.35,1.55);
                float a=clamp((core*.77*coreResponse +
                    aura*(.10+.35*glow)*auraResponse+whiteSpark*.28)
                    *brightness*musicalGain,0.0,.96);
                float edgeFade=110.0;
                a*=edgePresence;
                a*=1.0-smoothstep(40.0*max(spread,.3),edgeFade*max(spread,.3),abs(d));
                if(videoMode==1) {
                    // Even when the user explicitly opts into an animated design,
                    // constrain it to the very edge rather than covering video.
                    float inside=max(-d,0.0);
                    float outside=max(d,0.0);
                    a*=videoIntensity*.72*exp(-inside/max(videoWidth,1.0)*1.5);
                    a*=1.0-smoothstep(1.0,5.0,outside);
                    float light=max(base.r,max(base.g,base.b));
                    a*=smoothstep(.008,.085,light);
                }
                // RuntimeShader colors are premultiplied, preserving correct edge blending.
                return half4(half3(rgb*a),half(a));
            }
            
        """.trimIndent()
    }
}
