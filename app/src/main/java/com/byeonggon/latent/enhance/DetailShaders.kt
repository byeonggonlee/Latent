package com.byeonggon.latent.enhance

/**
 * The compute shaders of [GpuDetail], one per step of [DetailFilter]. Brightness and colour travel
 * as (brightness, red less brightness, blue less brightness): the same quantities the processor
 * works with, by the same formulas, so that both give the same picture.
 */
internal object DetailShaders {
    val HEADER = """
        #version 310 es
        precision highp float;
        precision highp int;
        precision highp image2D;
        precision highp sampler2D;
        layout(local_size_x = 16, local_size_y = 16) in;
        uniform ivec2 size;
        const vec3 LUMA = vec3($LUMA_RED, $LUMA_GREEN, $LUMA_BLUE);
        ivec2 here() { return ivec2(gl_GlobalInvocationID.xy); }
        bool outside(ivec2 p) { return p.x >= size.x || p.y >= size.y; }

    """.trimIndent() + "\n"

    private fun read(binding: Int, name: String) = "layout(rgba32f, binding = $binding) readonly uniform image2D $name;\n"

    private fun written(binding: Int, name: String) = "layout(rgba32f, binding = $binding) writeonly uniform image2D $name;\n"

    val ALL: Map<String, String> = mapOf(
        // An int holds a pixel as ARGB, uploaded as bytes B, G, R, A: the texel's red is the pixel's blue.
        "prepare" to """
            uniform sampler2D source;
            ${written(0, "base")}${written(1, "work")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec3 rgb = texelFetch(source, p, 0).bgr;
                float luma = dot(rgb, LUMA);
                vec4 value = vec4(luma, rgb.r - luma, rgb.b - luma, 0.0);
                imageStore(base, p, value);
                imageStore(work, p, value);
            }
        """,
        // The average over a row, of the pixels within reach that the picture has.
        "boxAcross" to """
            uniform int reach;
            ${read(0, "inImage")}${written(1, "outImage")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                int from = max(0, p.x - reach);
                int until = min(size.x - 1, p.x + reach);
                vec4 sum = vec4(0.0);
                for (int x = from; x <= until; x++) sum += imageLoad(inImage, ivec2(x, p.y));
                imageStore(outImage, p, sum / float(until - from + 1));
            }
        """,
        "boxDown" to """
            uniform int reach;
            ${read(0, "inImage")}${written(1, "outImage")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                int from = max(0, p.y - reach);
                int until = min(size.y - 1, p.y + reach);
                vec4 sum = vec4(0.0);
                for (int y = from; y <= until; y++) sum += imageLoad(inImage, ivec2(p.x, y));
                imageStore(outImage, p, sum / float(until - from + 1));
            }
        """,
        "noiseStats" to """
            ${read(0, "base")}${written(1, "stats")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                float i = imageLoad(base, p).r;
                imageStore(stats, p, vec4(i, i * i, 0.0, 0.0));
            }
        """,
        // A guided filter of brightness by itself: slope and offset from the local mean and variance.
        "selfCoefficients" to """
            uniform float edge;
            ${read(0, "means")}${written(1, "coefficients")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 m = imageLoad(means, p);
                float spread = m.g - m.r * m.r;
                float slope = spread / (max(0.0, spread) + edge);
                imageStore(coefficients, p, vec4(slope, m.r - slope * m.r, 0.0, 0.0));
            }
        """,
        "noiseApply" to """
            ${read(0, "base")}${read(1, "coefficients")}${written(2, "work")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 b = imageLoad(base, p);
                vec4 c = imageLoad(coefficients, p);
                imageStore(work, p, vec4(c.r * b.r + c.g, b.g, b.b, 0.0));
            }
        """,
        "colourStats" to """
            ${read(0, "base")}${written(1, "first")}${written(2, "second")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 b = imageLoad(base, p);
                imageStore(first, p, vec4(b.r, b.r * b.r, b.g, b.b));
                imageStore(second, p, vec4(b.r * b.g, b.r * b.b, 0.0, 0.0));
            }
        """,
        // Red and blue, as differences from the original brightness, guided by that brightness.
        "colourCoefficients" to """
            uniform float edge;
            ${read(0, "first")}${read(1, "second")}${written(2, "coefficients")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 a = imageLoad(first, p);
                vec4 b = imageLoad(second, p);
                float spread = max(0.0, a.g - a.r * a.r) + edge;
                float redSlope = (b.r - a.r * a.b) / spread;
                float blueSlope = (b.g - a.r * a.a) / spread;
                imageStore(coefficients, p, vec4(redSlope, a.b - redSlope * a.r, blueSlope, a.a - blueSlope * a.r));
            }
        """,
        "colourApply" to """
            uniform float amount;
            ${read(0, "base")}${read(1, "coefficients")}${read(2, "work")}${written(3, "next")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 b = imageLoad(base, p);
                vec4 c = imageLoad(coefficients, p);
                float red = b.g + (c.r * b.r + c.g - b.g) * amount;
                float blue = b.b + (c.b * b.r + c.a - b.b) * amount;
                imageStore(next, p, vec4(imageLoad(work, p).r, red, blue, 0.0));
            }
        """,
        "copyBrightness" to """
            ${read(0, "work")}${written(1, "copy")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                imageStore(copy, p, vec4(imageLoad(work, p).r));
            }
        """,
        // What sets a pixel apart from its area, strongest in the middle tones.
        "textureApply" to """
            uniform float textureAmount;
            ${read(0, "area")}${read(1, "work")}${written(2, "next")}
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 w = imageLoad(work, p);
                float value = w.r;
                float middle = clamp(4.0 * value * (1.0 - value), 0.0, 1.0);
                imageStore(next, p, vec4(value + textureAmount * middle * (value - imageLoad(area, p).r), w.g, w.b, 0.0));
            }
        """,
        // Against a 1-2-1 blur each way; differences too small to be more than noise are left mostly alone.
        "sharpen" to """
            uniform float sharpening;
            uniform float faintest;
            ${read(0, "work")}${written(1, "next")}
            float at(int x, int y) { return imageLoad(work, ivec2(clamp(x, 0, size.x - 1), clamp(y, 0, size.y - 1))).r; }
            float across(int x, int y) { return (at(x - 1, y) + 2.0 * at(x, y) + at(x + 1, y)) / 4.0; }
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                vec4 w = imageLoad(work, p);
                float soft = (across(p.x, p.y - 1) + 2.0 * across(p.x, p.y) + across(p.x, p.y + 1)) / 4.0;
                float apart = w.r - soft;
                float strength = abs(apart);
                imageStore(next, p, vec4(w.r + sharpening * apart * strength / (strength + faintest), w.g, w.b, 0.0));
            }
        """,
        // Back to pixels: from brightness and colour where the colour was smoothed, otherwise by the change in brightness.
        "compose" to """
            uniform sampler2D source;
            uniform int rowOffset;
            uniform int colour;
            ${read(0, "base")}${read(1, "work")}
            layout(rgba8, binding = 2) writeonly uniform image2D result;
            void main() {
                ivec2 p = here();
                if (outside(p)) return;
                ivec2 at = ivec2(p.x, p.y + rowOffset);
                vec4 original = texelFetch(source, at, 0);
                vec4 w = imageLoad(work, at);
                vec3 rgb;
                if (colour == 1) {
                    float red = w.r + w.g;
                    float blue = w.r + w.b;
                    rgb = vec3(red, (w.r - LUMA.r * red - LUMA.b * blue) / LUMA.g, blue);
                } else {
                    rgb = original.bgr + (w.r - imageLoad(base, at).r);
                }
                imageStore(result, p, vec4(rgb.b, rgb.g, rgb.r, original.a));
            }
        """,
    ).mapValues { it.value.trimIndent() }
}
