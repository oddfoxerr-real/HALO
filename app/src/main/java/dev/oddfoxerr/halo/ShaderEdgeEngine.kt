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
    private var videoShader: RuntimeShader? = null

    /** Video mode is color-sampled, not an audio FFT animation. */
    fun renderVideo(canvas: Canvas, width: Int, height: Int, bounds: RectF,
        colors: VideoEdgeColors, thickness: Float, spread: Float, glow: Float,
        brightness: Float): Boolean {
        if (disabled || !canvas.isHardwareAccelerated || width <= 0 || height <= 0) return false
        return try {
            val runtime = videoShader ?: RuntimeShader(VIDEO_SOURCE).also { videoShader = it }
            runtime.setFloatUniform("frame", bounds.left, bounds.top, bounds.right, bounds.bottom)
            runtime.setFloatUniform("width", (30f * thickness * spread).coerceIn(8f, 180f))
            runtime.setFloatUniform("softness", glow.coerceIn(0f, 1f))
            runtime.setFloatUniform("brightness", brightness.coerceIn(0f, 1f))
            fun rgb(c: Int) = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
            runtime.setFloatUniform("topColor", rgb(colors.top))
            runtime.setFloatUniform("rightColor", rgb(colors.right))
            runtime.setFloatUniform("bottomColor", rgb(colors.bottom))
            runtime.setFloatUniform("leftColor", rgb(colors.left))
            paint.shader = runtime
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            true
        } catch (failure: RuntimeException) {
            disabled = true
            paint.shader = null
            Log.e("HALO", "Video edge shader failed", failure)
            false
        }
    }

    private var disabled = false

    internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)
    internal fun compileVideoForTest(): RuntimeShader = RuntimeShader(VIDEO_SOURCE)

    fun render(canvas: Canvas, width: Int, height: Int, bounds: RectF, radius: Float,
        mode: EdgeMode, bands: FloatArray, bass: Float, phase: Float, seconds: Float,
        thickness: Float, spread: Float, glow: Float, brightness: Float,
        primary: Int, secondary: Int, attack: Float): Boolean {
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
            runtime.setIntUniform("design",mode.ordinal)
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
        private val VIDEO_SOURCE = """
            uniform float4 frame;
            uniform float width;
            uniform float softness;
            uniform float brightness;
            uniform float3 topColor;
            uniform float3 rightColor;
            uniform float3 bottomColor;
            uniform float3 leftColor;

            half4 main(float2 p) {
                float dl=abs(p.x-frame.x);
                float dr=abs(p.x-frame.z);
                float dt=abs(p.y-frame.y);
                float db=abs(p.y-frame.w);
                float w=max(width,2.0);
                float sl=exp(-pow(dl/w,2.0)*1.35);
                float sr=exp(-pow(dr/w,2.0)*1.35);
                float st=exp(-pow(dt/w,2.0)*1.35);
                float sb=exp(-pow(db/w,2.0)*1.35);
                float total=max(sl+sr+st+sb,.00001);
                float3 color=(sl*leftColor+sr*rightColor+st*topColor+sb*bottomColor)/total;
                float envelope=max(max(sl,sr),max(st,sb));
                float light=clamp(envelope*(.82+.18*softness)*brightness,0.0,.78);
                light *= smoothstep(.0,.09,max(color.r,max(color.g,color.b)));
                return half4(half3(color*light),half(light));
            }
        """.trimIndent()
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
            float periodicDistance(float u, float point) {
                return abs(fract(u-point+.5)-.5);
            }
            float gaussian(float x, float width) {
                float z=x/max(width,.0001);
                return exp(-2.0*z*z);
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
                // Background is genuinely transparent and needs no effect processing.
                if(abs(d)>max(48.0,thicknessPx*spread*100.0)) return half4(0.0);
            
                float lengthPx=1.0;
                float u=edgeParameter(px,lengthPx);
                float energy=clamp(harmonic(u),0.0,1.0);
                float kick=clamp(bass+attack*.6,0.0,1.5);
                float time=seconds*.85 + phase*.25;
                float volume=(.32+.85*energy+.95*kick)*max(spread,.25);
                float core=0.0, aura=0.0, sparkle=0.0;
                float travel=time*.45;
                float local=0.0;
            
                if(design==0) { // Luminous Dots — precise circular beads travelling around corners
                    float dot=disc(u,d,62.0,travel*13.0,0.0,2.8*thicknessPx*volume,lengthPx);
                    core=dot;
                    aura=gaussian(length(float2((fract(u*62.0-travel*13.0)-.5)*lengthPx/62.0,d)),12.0*thicknessPx*volume);
                    sparkle=pow(dot,3.0)*(.34+.66*kick);
                } else if(design==1) { // Constellation — drifting, scattered individual point lights
                    float p=disc(u,d,84.0,travel*4.4,-6.0*thicknessPx,2.2*thicknessPx,lengthPx);
                    float q=disc(u,d,51.0,-travel*2.7,7.0*thicknessPx,3.0*thicknessPx,lengthPx);
                    float stars=max(p,q*.8);
                    float twinkle=.65+.35*sin(TAU*(u*17.0-time*.7));
                    core=stars*twinkle;
                    aura=stars*.36+gaussian(d,18.0*thicknessPx)*.035;
                    sparkle=pow(stars,4.0)*twinkle;
                } else if(design==2) { // Fireflies — loose luminous orbiting orbs
                    float a=disc(u,d,34.0,travel*6.0,5.0*thicknessPx,3.4*thicknessPx*(1.0+energy),lengthPx);
                    float b=disc(u,d,23.0,-travel*4.1,-8.0*thicknessPx,2.2*thicknessPx,lengthPx);
                    core=max(a,b*.8);
                    aura=gaussian(d,21.0*thicknessPx)*(.04+.10*energy)+max(a,b)*.7;
                    sparkle=pow(core,2.0)*.5;
                } else if(design==3) { // Meteor shower — asymmetric heads plus broad fading tails
                    float head=fract(time*.11);
                    float tail=fract(head-u+1.0);
                    float streak=exp(-tail*24.0)*gaussian(d,4.0*thicknessPx*(1.0+kick));
                    float headLight=gaussian(periodicDistance(u,head)*lengthPx,6.0*thicknessPx)
                        *gaussian(d,5.0*thicknessPx);
                    float second=fract(head+.43);
                    float tail2=fract(second-u+1.0);
                    core=max(streak,exp(-tail2*35.0)*gaussian(d+4.0*thicknessPx,3.0*thicknessPx)*.6);
                    sparkle=headLight;
                    aura=streak*.45+headLight*.6;
                } else if(design==4) { // Liquid Glass — organically folded reflective surface
                    float3 domain=float3(u*8.0,d*.035,time*.19);
                    float a=noise3(domain), b=noise3(domain*2.2+float3(1.3,2.4,3.7));
                    float shifted=d+(a*.75+b*.25)*13.0*thicknessPx*volume;
                    core=gaussian(shifted,3.8*thicknessPx)*(1.0+.25*energy);
                    aura=gaussian(shifted,15.0*thicknessPx)*(.35+.25*kick);
                    sparkle=pow(max(0.0,a*.55+b*.45),3.0)*core;
                } else if(design==5) { // Aurora Flow — continuously evolving spectral curtains
                    float3 domain=float3(u*7.0,d*.023,time*.13);
                    float a=noise3(domain), b=noise3(domain*2.0+float3(2.3,1.8,4.4));
                    float fold=d+a*20.0*thicknessPx*volume+b*8.0*thicknessPx;
                    core=gaussian(fold,3.3*thicknessPx)*(.6+.4*energy);
                    aura=gaussian(fold-9.0*thicknessPx,24.0*thicknessPx)*(.45+.35*kick);
                    sparkle=pow(max(.0,a*.75+b*.25),4.0)*aura;
                } else if(design==6) { // Chromatic Prism — three refracted optical channels
                    float sweep=sin(TAU*(u*3.0-time*.16));
                    float wobble=sweep*3.4*thicknessPx;
                    float a=gaussian(d+wobble-5.0*thicknessPx,3.0*thicknessPx);
                    float b=gaussian(d+wobble,3.2*thicknessPx);
                    float c=gaussian(d+wobble+5.0*thicknessPx,2.8*thicknessPx);
                    core=.48*a+.9*b+.45*c;
                    aura=gaussian(d+wobble,20.0*thicknessPx)*.37;
                    sparkle=b*.5;
                } else if(design==7) { // Silk Ribbons — intertwined fluid sheets, no stroked lines
                    float field=sin(TAU*(u*4.0-time*.22));
                    float wave=sin(TAU*(u*7.0+time*.13)+field);
                    float s1=d-7.0*thicknessPx-field*9.0*thicknessPx*energy;
                    float s2=d+8.0*thicknessPx-wave*8.0*thicknessPx*(.4+energy);
                    core=gaussian(s1,3.3*thicknessPx)+gaussian(s2,3.1*thicknessPx)*.7;
                    aura=gaussian(s1,14.0*thicknessPx)*.3+gaussian(s2,13.0*thicknessPx)*.22;
                    sparkle=gaussian(s1,2.0*thicknessPx)*(.2+.4*kick);
                } else if(design==8) { // Pulse Bloom — a shockwave expands with the bass
                    float ring=sin(d*.15/thicknessPx-time*6.0);
                    float ridge=abs(ring);
                    core=gaussian(d-5.0*thicknessPx*kick,3.4*thicknessPx)
                        +pow(1.0-ridge,7.0)*gaussian(d,21.0*thicknessPx)*(.3+.7*kick);
                    aura=gaussian(d,18.0*thicknessPx)*(.25+.5*kick);
                    sparkle=core*.12;
                } else if(design==9) { // Neon Current — smooth continuous luminous plasma
                    float variation=.5+.5*sin(TAU*(u*2.0-time*.18));
                    core=gaussian(d,3.1*thicknessPx)*(.7+.3*variation);
                    aura=gaussian(d,18.0*thicknessPx)*(.28+.25*variation+.22*kick);
                    sparkle=gaussian(d,1.25*thicknessPx)*variation*.25;
                } else if(design==10) { // Pixel Matrix — individually bounded LED cells
                    float grid=u*78.0-time*.8;
                    float cell=fract(grid)-.5;
                    float cellIndex=floor(grid);
                    float band=harmonic(fract((cellIndex+.5)/78.0));
                    float dy=abs(d)-4.5*thicknessPx*(.5+band);
                    float rectangle=max(abs(cell)*lengthPx/78.0-2.5*thicknessPx,dy);
                    core=1.0-smoothstep(-.5,1.2*thicknessPx,rectangle);
                    aura=gaussian(rectangle,7.0*thicknessPx)*.19;
                    sparkle=core*(.12+.30*kick);
                } else { // Halo Rings — radiating concentric light
                    float ripple=cos(d*.13/thicknessPx-time*3.6);
                    core=gaussian(d,3.6*thicknessPx)+pow(max(0.0,ripple),12.0)
                        *gaussian(d,26.0*thicknessPx)*(.2+.75*kick);
                    aura=gaussian(d,26.0*thicknessPx)*(.22+.43*kick);
                    sparkle=gaussian(d,2.1*thicknessPx)*.25;
                }
            
                // Fix v0.13's hard angular gradient jumps: colors use physical perimeter
                // distance with cosine interpolation, periodic at exactly u=0 and u=1.
                float chroma=.5-.5*cos(TAU*(u-time*.035));
                float3 base=mix(first,second,chroma);
                float whiteSpark=clamp(sparkle*(.48+.45*energy+.4*kick),0.0,.85);
                float3 rgb=mix(base,float3(1.0),whiteSpark);
                // Glow is an integral part of the material instead of a second Canvas layer.
                // A pronounced attack response, not an always-bright idle loop.
                float transient=clamp(attack,0.0,1.5);
                float musicalGain=clamp(.22+.58*energy+.50*kick+.48*transient,.18,1.4);
                float a=clamp((core*.77 + aura*(.10+.35*glow)+whiteSpark*.28)
                    *brightness*musicalGain,0.0,.96);
                a*=1.0-smoothstep(40.0*max(spread,.3),92.0*max(spread,.3),abs(d));
                // RuntimeShader colors are premultiplied, preserving correct edge blending.
                return half4(half3(rgb*a),half(a));
            }
            
        """.trimIndent()
    }
}
