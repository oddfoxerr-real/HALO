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
        dynamicForce: Float = 2.4f, pixelDensity: Float = 1f): Boolean {
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
                float level=clamp(max(max(low,mid),high)*.8+hit*.35,0.0,1.0);
                float gain=level;
                // A visible, gentle no-capture mode remains explicitly distinct from
                // beat response. No timer-generated beats enter the music envelopes.
                if(ambientMotion>.5 || videoMode==1) {
                    float breath=.5-.5*cos(seconds*.9);
                    gain=max(gain,.28+.06*breath);
                    low=max(low,.10+.05*breath);
                }
                if(gain<.001) return half4(0.0);
                float limit=min(20.0*unit,max(10.0*unit,line*6.0));
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
                if(design==1) {
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
                    float count=compact>.5 ? 12.0 : clamp(floor(perimeter/(15.0*unit)),24.0,320.0);
                    float cell=floor(u*count);
                    float beadU=(cell+.5)/count;
                    float along=(fract(u*count)-.5)*perimeter/count;
                    float local=clamp(harmonic(beadU)*dynamicForce*.6,0.0,1.0);
                    float rad=min(perimeter/count*.23,line*(.9+.55*local+.35*high));
                    shapeDistance=length(float2(along,d))-rad;
                    float sheen=pow(.5+.5*cos(TAU*(beadU-seconds*.025)),6.0);
                    weight=.40+.38*local+.22*sheen;
                    highlight=sheen*high*.26;
                    tint=.5-.5*cos(TAU*beadU);
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
                float alpha=(coverage*.80+bloom*.17*glow)*weight*brightness*gain;
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
                if (design!=0 && abs(d)>20.0*max(pixelDensity,.5)) return half4(0.0);
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
