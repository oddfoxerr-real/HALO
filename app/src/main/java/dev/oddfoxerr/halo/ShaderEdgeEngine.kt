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
 * Native AGSL (GPU) design engine. Unlike the previous Canvas pipeline, light and geometry
 * are generated together in one procedural field with transparent pixels inside the display.
 * Built from scratch using public Android RuntimeShader APIs, not copied shaders/assets.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ShaderEdgeEngine {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var shader: RuntimeShader? = null
    private var failed = false
    val hasFailed: Boolean get() = failed

    /** Compile on the Android framework in instrumentation tests, not JVM stubs. */
    internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)

    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        bounds: RectF,
        radius: Float,
        mode: EdgeMode,
        bands: FloatArray,
        bass: Float,
        phase: Float,
        seconds: Float,
        thickness: Float,
        spread: Float,
        glow: Float,
        brightness: Float,
        primary: Int,
        secondary: Int,
        dotSize: Float,
        dotDensity: Float
    ): Boolean {
        if (failed || width <= 0 || height <= 0) return false
        try {
            val runtime = shader ?: RuntimeShader(SOURCE).also { shader = it }
            runtime.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            runtime.setFloatUniform("frame", bounds.left, bounds.top, bounds.right, bounds.bottom)
            runtime.setFloatUniform("radius", radius)
            runtime.setFloatUniform("seconds", seconds)
            runtime.setFloatUniform("phase", phase)
            runtime.setFloatUniform("bass", bass.coerceIn(0f, 1f))
            runtime.setFloatUniform("thickness", thickness)
            runtime.setFloatUniform("spread", spread)
            runtime.setFloatUniform("glow", glow)
            runtime.setFloatUniform("brightness", brightness)
            runtime.setFloatUniform("dotSize", dotSize.coerceIn(.55f, 2.5f))
            runtime.setFloatUniform("dotDensity", dotDensity.coerceIn(.6f, 2.0f))
            runtime.setIntUniform("design", mode.ordinal)
            val frequencies = FloatArray(4)
            for (i in bands.indices) {
                val index = (i * 4 / max(1, bands.size)).coerceIn(0, 3)
                frequencies[index] = max(frequencies[index], bands[i].coerceIn(0f, 1f))
            }
            runtime.setFloatUniform("audio", frequencies)
            runtime.setFloatUniform("first", Color.red(primary) / 255f,
                Color.green(primary) / 255f, Color.blue(primary) / 255f)
            runtime.setFloatUniform("second", Color.red(secondary) / 255f,
                Color.green(secondary) / 255f, Color.blue(secondary) / 255f)
            paint.shader = runtime
            paint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            return true
        } catch (failure: RuntimeException) {
            Log.e("HALO/AGSL", "GPU visualizer runtime error", failure)
            // Never silently show obsolete Canvas animations when shader compilation fails.
            failed = true
            paint.shader = null
            return false
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
            uniform float4 audio;
            uniform float thickness;
            uniform float spread;
            uniform float glow;
            uniform float brightness;
            uniform float dotSize;
            uniform float dotDensity;
            uniform float3 first;
            uniform float3 second;
            uniform int design;

            float hash(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f*f*(3.0-2.0*f);
                float a=hash(i), b=hash(i+float2(1,0));
                float c=hash(i+float2(0,1)), d=hash(i+float2(1,1));
                return mix(mix(a,b,u.x),mix(c,d,u.x),u.y);
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
                float2 center = 0.5 * (frame.xy + frame.zw);
                float2 extent = max((frame.zw - frame.xy)*0.5 - radius, float2(1.0));
                float2 q = abs(p-center) - extent;
                return length(max(q, float2(0.0))) + min(max(q.x,q.y),0.0) - radius;
            }

            float harmonic(float x) {
                float4 a = audio;
                float low = mix(a.x,a.y,smoothstep(0.0,0.5,x));
                float high = mix(a.z,a.w,smoothstep(0.5,1.0,x));
                return mix(low,high,smoothstep(0.20,0.80,x));
            }

            // Continuous arc-length around a rounded rectangle. Dots and gradients
            // use the same physical distance at straight edges AND at rounded corners.
            float perimeterLocation(float2 p) {
                float r = max(radius, 1.0);
                float w = max(frame.z - frame.x - 2.0*r, 1.0);
                float h = max(frame.w - frame.y - 2.0*r, 1.0);
                float a = 1.570796327 * r;
                float full = 2.0*w + 2.0*h + 4.0*a;
                float left = frame.x, top = frame.y;
                float right = frame.z, bottom = frame.w;
                float dist;
                if (p.x > right-r && p.y < top+r) {
                    float angle = atan(p.y-(top+r),p.x-(right-r));
                    dist = w + (angle+1.570796327)*r;
                } else if (p.x > right-r && p.y > bottom-r) {
                    float angle = atan(p.y-(bottom-r),p.x-(right-r));
                    dist = w+a+h+angle*r;
                } else if (p.x < left+r && p.y > bottom-r) {
                    float angle = atan(p.y-(bottom-r),p.x-(left+r));
                    dist = w+2.0*a+h+w+(angle-1.570796327)*r;
                } else if (p.x < left+r && p.y < top+r) {
                    float angle = atan(p.y-(top+r),p.x-(left+r));
                    dist = 2.0*w+3.0*a+2.0*h+(angle+3.141592654)*r;
                } else {
                    float dt = abs(p.y-top), db = abs(p.y-bottom);
                    float dr = abs(p.x-right), dl = abs(p.x-left);
                    float closest = min(min(dt,db),min(dr,dl));
                    if (dt <= closest) dist = clamp(p.x-left-r,0.0,w);
                    else if (dr <= closest) dist = w+a+clamp(p.y-top-r,0.0,h);
                    else if (db <= closest) dist = w+a+h+a+clamp(right-r-p.x,0.0,w);
                    else dist = 2.0*w+2.0*a+h+a+clamp(bottom-r-p.y,0.0,h);
                }
                return fract(dist/full);
            }

            float wrappedDistance(float a, float b) {
                return abs(fract(a-b+0.5)-0.5);
            }

            float gaussian(float d, float width) {
                float x=d/max(width,0.1);
                return exp(-x*x);
            }

            half4 main(float2 px) {
                float d = roundedDistance(px);
                // Do not allocate the center of the display to expensive shaders.
                if (abs(d)>max(62.0, 165.0*thickness*spread)) return half4(0.0);
                float u = perimeterLocation(px);
                float perimeter = 2.0*(frame.z-frame.x+frame.w-frame.y-4.0*radius)
                    + 6.2831853*radius;
                perimeter = max(perimeter, 1.0);
                float time=seconds*.55;
                float low=clamp(audio.x,0.0,1.0);
                float treble=clamp(audio.w,0.0,1.0);
                float energy=clamp(harmonic(u),0.0,1.0);
                float beat=clamp(bass,0.0,1.0);
                float width=(5.0+12.0*energy+8.0*beat)*max(thickness,0.2)*spread;
                float activity=.35+.65*max(beat,energy);
                float amount=0.0;
                float bright=0.0;
                float shine=0.0;

                if (design == 0) { // Liquid glass: organic refraction, not a sketched wave
                    float3 coord=float3(u*9.0,d*.025,time*.3);
                    float warp=noise3(coord)*.72+noise3(coord*2.1+float3(1.5,3.0,0.0))*.28;
                    float warpD=d-warp*width*.67;
                    amount=gaussian(warpD-width*.17,width*.42)*(.50+.5*activity);
                    bright=gaussian(warpD+width*.12,width*.16)*(.32+.68*energy);
                    shine=gaussian(warpD-width*.27,width*.09)*.18;
                } else if (design == 1) { // Dot matrix: three physically separated rows
                    float columns=max(42.0, round(112.0*dotDensity));
                    float cycle=u*columns-time*.63;
                    float cell=floor(cycle);
                    float dx=(fract(cycle)-.5)*perimeter/columns;
                    float glimmer=.60+.40*sin(time*1.7+cell*.83);
                    float strength=.40+.60*energy;
                    float radiusPx=(1.25+3.3*strength)*dotSize*thickness;
                    for(int row=0;row<3;row++){
                        float dy=d-(float(row)-1.0)*(8.5*dotSize*thickness);
                        float dotDist=length(float2(dx,dy));
                        float lit=gaussian(max(dotDist-radiusPx,0.0),1.3*thickness)
                            *(.58+.42*glimmer)*strength;
                        amount=max(amount,lit);
                        bright=max(bright,gaussian(dotDist,radiusPx*.50)*lit);
                    }
                } else if (design == 2) { // Fireflies: layered drifting particle field
                    float cells=round(76.0*dotDensity);
                    float z=u*cells-time*.41;
                    float id=floor(z);
                    float offset=(fract(z)-.5)*perimeter/cells;
                    float3 seed=float3(id,1.6,3.7);
                    float floatY=(noise3(seed+float3(0.0,0.0,time*.32)))*22.0;
                    float y=d-floatY;
                    float rad=(2.5+4.0*hash(float2(id,6.5)))*dotSize*thickness;
                    float particle=gaussian(length(float2(offset,y)),rad);
                    float z2=u*round(47.0*dotDensity)+time*.26;
                    float id2=floor(z2);
                    float x2=(fract(z2)-.5)*perimeter/max(1.0,round(47.0*dotDensity));
                    float y2=d-(hash(float2(id2,17.0))-.5)*33.0;
                    amount=particle*(.45+.55*activity)+
                        gaussian(length(float2(x2,y2)),3.2*dotSize*thickness)*.7*activity;
                    bright=amount*.65;
                } else if (design == 3) { // Meteor shower: streaming streaks
                    float head=fract(time*.19);
                    float distance=fract(head-u+1.0);
                    float lane=(.3+.7*hash(float2(floor(u*18.0),3.0)));
                    float trail=exp(-distance*24.0)*lane;
                    amount=gaussian(d-4.0*thickness,width*.35)*trail*(.5+.5*activity);
                    bright=gaussian(distance*perimeter,19.0*thickness)*gaussian(d,6.0*thickness);
                } else if (design == 4) { // Aurora: volumetric noise curtains
                    float3 coord=float3(u*10.5,d*.027,time*.22);
                    float coarse=noise3(coord);
                    float detailed=noise3(coord*2.0+float3(1.3,6.0,2.6));
                    float curtain=d-(coarse*.72+detailed*.28)*width*1.15;
                    float translucent=smoothstep(-.7,.8,coarse*.7+detailed*.3);
                    amount=gaussian(curtain-width*.18,width*.66)*translucent*(.55+.45*activity);
                    bright=gaussian(curtain+width*.12,width*.20)*translucent*.52;
                } else if (design == 5) { // Plasma: turbulently folded energy
                    float3 field=float3(u*16.0,d*.045,time*.45);
                    float n1=noise3(field);
                    float n2=noise3(field*2.3+float3(3.0,1.0,2.0));
                    float shape=d+(n1*.7+n2*.3)*width*.8;
                    amount=gaussian(shape,width*.58)*(.57+.43*activity);
                    bright=gaussian(shape,width*.17)*(.3+.7*low);
                    shine=gaussian(n2-.5,.21)*amount*.45;
                } else if (design == 6) { // Prism: sharp moving facets
                    float facets=fract(u*46.0-time*.16);
                    float facetEdge=abs(facets-.5)*2.0;
                    float facetShape=d-(.50-facetEdge)*width*.8;
                    amount=gaussian(facetShape,width*.24)
                        *(.3+.7*pow(facetEdge,1.8))*(.4+.6*energy);
                    bright=gaussian(facetShape,width*.09)*facetEdge;
                } else if (design == 7) { // Ripple: expanding beat interference
                    float wave=sin(d*.24-time*5.1)*.5+.5;
                    float rings=pow(wave,8.0);
                    amount=gaussian(d,width*1.05)*(.28+.72*rings)*(.48+.52*beat);
                    bright=gaussian(d,width*.22)*rings*.64;
                } else if (design == 8) { // Spectrum: peaks follow four FFT bands
                    float columns=88.0;
                    float cell=fract(u*columns);
                    float bandShape=energy*23.0*spread*thickness+3.0*thickness;
                    float x=(cell-.5)*perimeter/columns;
                    float rectangle=step(abs(x),perimeter/(columns*2.0)*.72);
                    float bar=1.0-smoothstep(bandShape-1.0,bandShape+2.0,abs(d));
                    amount=bar*rectangle*(.28+.72*energy);
                    bright=amount*.38;
                } else if (design == 9) { // Comets: two bright heads and decay trails
                    float q1=fract(time*.24-u+1.0);
                    float q2=fract(-time*.17-u+.55+2.0);
                    float tail=exp(-q1*19.0)+exp(-q2*25.0);
                    amount=gaussian(d,width*.24)*tail*(.55+.45*activity);
                    bright=(gaussian(wrappedDistance(u,fract(time*.24))*perimeter,15.0)
                        +gaussian(wrappedDistance(u,fract(-time*.17+.55))*perimeter,17.0))
                        *gaussian(d,5.0*thickness);
                } else if (design == 10) { // Orbitals: opposing current lines
                    float head1=wrappedDistance(u,fract(time*.23));
                    float head2=wrappedDistance(u,fract(.52-time*.19));
                    float l1=gaussian(head1*perimeter,perimeter*.07);
                    float l2=gaussian(head2*perimeter,perimeter*.07);
                    float depth=5.0*thickness*sin(u*32.0-time*2.2);
                    amount=gaussian(d-depth,width*.28)*max(l1,l2)*(.48+.52*activity);
                    bright=gaussian(d+depth,width*.16)*(l1+l2)*.32;
                } else { // Nebula: cloud-like transparent gas
                    float3 c=float3(u*7.0,d*.022,time*.14);
                    float n1=noise3(c);
                    float n2=noise3(c*2.8+float3(4.0,7.0,0.0));
                    float cloud=smoothstep(-.45,.52,n1*.7+n2*.3);
                    amount=gaussian(d,width*1.75)*cloud*(.32+.68*activity)*.82;
                    bright=gaussian(d-width*.27,width*.46)*cloud*.27;
                }

                // Color is mixed in approximate linear light, with an integer number of
                // smooth cycles: no hard fract() gradient seam, no muddy midpoint or white wash.
                float cycle=u*2.0-phase*.07;
                float blend=.5-.5*cos(cycle*6.2831853);
                blend=blend*blend*(3.0-2.0*blend);
                float3 base=sqrt(mix(first*first,second*second,blend));
                float hueDetail=.14*sin(u*12.5663706-time*.30);
                base=mix(base,sqrt(mix(first*first,second*second,
                    clamp(blend+hueDetail,0.0,1.0))),.22);
                float3 reflected=mix(base,float3(1.0),clamp(bright*.22+shine*.08,0.0,.27));
                float alpha=clamp(amount+bright*.27+shine*.1,0.0,.91);
                alpha *= clamp(brightness,0.1,1.0);
                // Stable translucent diffusion around the true generated geometry.
                float ambient=(design == 1 || design == 2) ? amount : gaussian(d,width*1.35)*activity;
                alpha += clamp(glow,0.0,1.0)*.085*ambient;
                alpha=clamp(alpha,0.0,.94);
                return half4(half3(reflected)*half(alpha),half(alpha));
            }
        """.trimIndent()
    }
}
