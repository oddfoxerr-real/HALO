# Third-party shader notices

HALO uses an original procedural signed-distance GPU visualizer and contains one adapted,
MIT-licensed 3D simplex-noise utility in its Aurora effect.

## Audio Shader Studio

- Source: https://github.com/sandner-art/Audio-Shader-Studio
- File: shaders/geometric-patterns/fluidic-1.glsl
- Adaptation: `GpuEdgeRenderer.kt` (renamed GLSL helpers and adapted to Android AGSL
  numeric/vector types, transparent screen-edge lighting and HALO's audio uniforms)
- License: MIT

MIT License

Copyright (c) 2025 Daniel Sandner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Additional open-source visualizer references

These were researched as candidates and are **not bundled or copied into HALO**:

- Fosfora — https://github.com/kevinraymond/fosfora — MIT OR Apache-2.0.
  High-quality WGSL shader/particle architecture, but desktop Vulkan/Metal output
  is not a direct drop-in for Android's AGSL edge overlay.
- Shader Editor — https://github.com/markusfisch/ShaderEditor — MIT.
  Android GLSL experimentation, previous-frame feedback and shader previews.
- ProjectM Android — https://f-droid.org/en/packages/nl.neerdael.projectm/ —
  LGPL-2.1; the preset ecosystem has separate licensing to review. Not integrated.
- SRE-0 Audio Visualizer — https://github.com/SRE-0/Audio-Visualizer — MIT.
  Native audio-analysis/rendering architecture, not HALO's rendering source.
