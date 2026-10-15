package com.byeonggon.latent.enhance

/**
 * The compute shader of [GpuTone]: [ToneRenderer.render] for one pixel, step by step as the processor
 * does it. The tables the steps look things up in are the processor's own, handed over as they are.
 */
internal object ToneShader {
    /** The shader for a picture given as levels of light, or with [displayed], as finished pixels the camera rendered. */
    fun source(displayed: Boolean): String = SOURCE.replaceFirst("#version 310 es\n", "#version 310 es\n" + if (displayed) "#define DISPLAYED 1\n" else "")

    private val SOURCE = """
        #version 310 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;
        precision highp sampler2D;
        precision highp image2D;
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(std430, binding = 0) readonly buffer Tables { float t[]; };
        struct Stage {
            int lut; int gain; int share; int stretch; int clear; int around; int near; int colorful;
            float saturation; float vibrance; float clarity; float evenHaze; float airRed; float airGreen; float airBlue; float haze;
        };
        layout(std430, binding = 1) readonly buffer Stages { Stage stages[]; };
        struct Local {
            int shape; int inverted; int raster; int rasterWidth; int rasterHeight; float hue; float hueSpread; int hueOn;
            float startX; float startY; float endX; float endY; float feather; float darkest; float brightest; float unused3;
        };
        layout(std430, binding = 2) readonly buffer Locals { Local locals[]; };

        #ifdef DISPLAYED
        // Bytes B, G, R, A of an ARGB int; back to the light they were made from by the camera's curve, as LinearImage.fromArgb does.
        uniform sampler2D displayed;
        layout(std430, binding = 3) readonly buffer Levels { int levelOf[]; };
        #else
        uniform usampler2D redPlane;
        uniform usampler2D greenPlane;
        uniform usampler2D bluePlane;
        #endif
        layout(rgba8, binding = 0) writeonly uniform image2D result;

        uniform ivec2 size;
        uniform int bandTop;
        uniform int frameTop;
        uniform int frameHeight;
        uniform int frameLeft;
        uniform int frameWidth;
        uniform int mapWidth;
        uniform int mapHeight;
        uniform int localCount;
        uniform int monochrome;
        uniform int mixer;
        uniform int grading;
        uniform int vignette;
        uniform int grain;
        uniform float fleckSize;
        uniform float grainStrength;
        uniform float placeLeft;
        uniform float placeTop;
        uniform float turnedWidth;
        uniform float turnedHeight;
        uniform float cosA;
        uniform float sinA;
        uniform float tiltAcross;
        uniform float tiltDown;
        uniform float mirror;
        uniform float wholeWidth;
        uniform float wholeHeight;
        uniform int turns;

        const vec3 LUMA = vec3($LUMA_RED, $LUMA_GREEN, $LUMA_BLUE);
        const int LEVELS = ${LinearImage.LEVELS};

        // Where this pixel falls between the small copy's pixels that the guided maps are kept at.
        int mapLeft; int mapRight; float mapAcross; int mapUpper; int mapLower; float mapDown;

        vec2 mapAt(int map) {
            if (map < 0) return vec2(0.0);
            int plane = mapWidth * mapHeight;
            float slopeAbove = t[map + mapUpper + mapLeft] + (t[map + mapUpper + mapRight] - t[map + mapUpper + mapLeft]) * mapAcross;
            float slopeBelow = t[map + mapLower + mapLeft] + (t[map + mapLower + mapRight] - t[map + mapLower + mapLeft]) * mapAcross;
            int o = map + plane;
            float offsetAbove = t[o + mapUpper + mapLeft] + (t[o + mapUpper + mapRight] - t[o + mapUpper + mapLeft]) * mapAcross;
            float offsetBelow = t[o + mapLower + mapLeft] + (t[o + mapLower + mapRight] - t[o + mapLower + mapLeft]) * mapAcross;
            return vec2(slopeAbove + (slopeBelow - slopeAbove) * mapDown, offsetAbove + (offsetBelow - offsetAbove) * mapDown);
        }

        int stepOf(float level, int steps) { return clamp(int(level * float(steps)), 0, steps); }

        vec3 shade(Stage s, uvec3 level) {
            float r = t[s.lut + int(level.r)];
            float g = t[s.lut + LEVELS + int(level.g)];
            float b = t[s.lut + 2 * LEVELS + int(level.b)];
            if (s.haze > 0.5) {
                float stretch;
                if (s.clear < 0) {
                    stretch = s.evenHaze;
                } else {
                    vec2 clear = mapAt(s.clear);
                    stretch = t[s.stretch + stepOf(clear.x * (LUMA.r * r + LUMA.g * g + LUMA.b * b) + clear.y, 256)];
                }
                r = max(0.0, r + (r - s.airRed) * stretch);
                g = max(0.0, g + (g - s.airGreen) * stretch);
                b = max(0.0, b + (b - s.airBlue) * stretch);
            }
            if (s.gain >= 0 || s.near >= 0) {
                float luma = LUMA.r * r + LUMA.g * g + LUMA.b * b;
                float gain = 1.0;
                if (s.gain >= 0) {
                    vec2 around = mapAt(s.around);
                    gain = 1.0 + (t[s.gain + stepOf(around.x * luma + around.y, 1024)] - 1.0) * t[s.share + stepOf(luma, 1024)];
                }
                if (s.near >= 0 && luma > 0.0) {
                    vec2 near = mapAt(s.near);
                    float apart = luma - (near.x * luma + near.y);
                    float middle = clamp(4.0 * luma * (1.0 - luma), 0.0, 1.0);
                    gain *= max(0.0, 1.0 + s.clarity * middle * apart / luma);
                }
                r *= gain;
                g *= gain;
                b *= gain;
            }
            if (s.colorful != 0) {
                float luma = LUMA.r * r + LUMA.g * g + LUMA.b * b;
                float factor = s.saturation;
                if (s.vibrance != 0.0) {
                    float most = max(r, max(g, b));
                    if (most > 0.0) factor *= 1.0 + s.vibrance * min(r, min(g, b)) / most;
                }
                r = luma + (r - luma) * factor;
                g = luma + (g - luma) * factor;
                b = luma + (b - luma) * factor;
            }
            return vec3(r, g, b);
        }

        float eased(float share) {
            float within = clamp(share, 0.0, 1.0);
            return within * within * (3.0 - 2.0 * within);
        }

        float maskWeight(Local m, float x, float y) {
            float covered;
            if (m.shape == 3) {
                covered = 1.0;
            } else if (m.shape == 1) {
                float column = clamp(x * float(m.rasterWidth) - 0.5, 0.0, float(m.rasterWidth - 1));
                float row = clamp(y * float(m.rasterHeight) - 0.5, 0.0, float(m.rasterHeight - 1));
                int left = int(column);
                int top = int(row);
                int right = min(left + 1, m.rasterWidth - 1);
                int bottom = min(top + 1, m.rasterHeight - 1);
                float across = column - float(left);
                float down = row - float(top);
                int c = m.raster;
                float upper = t[c + top * m.rasterWidth + left] + (t[c + top * m.rasterWidth + right] - t[c + top * m.rasterWidth + left]) * across;
                float lower = t[c + bottom * m.rasterWidth + left] + (t[c + bottom * m.rasterWidth + right] - t[c + bottom * m.rasterWidth + left]) * across;
                covered = upper + (lower - upper) * down;
            } else {
                float across = (m.endX - m.startX) * wholeWidth;
                float down = (m.endY - m.startY) * wholeHeight;
                if (m.shape == 0) {
                    float length = across * across + down * down;
                    covered = length <= 0.0 ? 0.0 : 1.0 - eased(((x - m.startX) * wholeWidth * across + (y - m.startY) * wholeHeight * down) / length);
                } else if (across == 0.0 || down == 0.0) {
                    covered = 0.0;
                } else {
                    float fromX = (x - m.startX) * wholeWidth / across;
                    float fromY = (y - m.startY) * wholeHeight / down;
                    float distance = sqrt(fromX * fromX + fromY * fromY);
                    covered = m.feather <= 0.0 ? (distance <= 1.0 ? 1.0 : 0.0) : 1.0 - eased((distance - (1.0 - m.feather)) / m.feather);
                }
            }
            return m.inverted != 0 ? 1.0 - covered : covered;
        }

        float toneWeight(Local m, float luma) {
            if (m.darkest <= 0.0 && m.brightest >= 1.0) return 1.0;
            float below = m.darkest <= 0.0 ? 1.0 : clamp((luma - m.darkest) / 0.1 + 1.0, 0.0, 1.0);
            float above = m.brightest >= 1.0 ? 1.0 : clamp((m.brightest - luma) / 0.1 + 1.0, 0.0, 1.0);
            return below * above;
        }

        float lightOf(vec3 c) { return LUMA.r * c.r * c.r + LUMA.g * c.g * c.g + LUMA.b * c.b * c.b; }

        float hueOf(vec3 c, float most, float chroma) {
            float hue;
            if (most == c.r) hue = (c.g - c.b) / chroma;
            else if (most == c.g) hue = 2.0 + (c.b - c.r) / chroma;
            else hue = 4.0 + (c.r - c.g) / chroma;
            return hue < 0.0 ? hue + 6.0 : hue;
        }

        float colourWeight(Local m, vec3 c) {
            if (m.hueOn == 0) return 1.0;
            vec3 v = max(c, vec3(0.0));
            float most = max(v.r, max(v.g, v.b));
            float chroma = most - min(v.r, min(v.g, v.b));
            if (most <= 0.0 || chroma <= 0.0) return 0.0;
            float degrees = hueOf(v, most, chroma) * 60.0;
            float apart = abs(degrees - m.hue);
            apart = min(apart, 360.0 - apart);
            float near = clamp((m.hueSpread + 20.0 - apart) / 20.0, 0.0, 1.0);
            float vivid = clamp((chroma / most - 0.05) / 0.15, 0.0, 1.0);
            return near * vivid;
        }

        vec3 colorOf(float hue, float vividness, float most) {
            int sector = clamp(int(hue), 0, 5);
            float within = hue - float(sector);
            float least = most * (1.0 - vividness);
            float falling = most * (1.0 - vividness * within);
            float rising = most * (1.0 - vividness * (1.0 - within));
            if (sector == 0) return vec3(most, rising, least);
            if (sector == 1) return vec3(falling, most, least);
            if (sector == 2) return vec3(least, most, rising);
            if (sector == 3) return vec3(least, falling, most);
            if (sector == 4) return vec3(rising, least, most);
            return vec3(most, least, falling);
        }

        vec3 mixed(vec3 c) {
            float most = max(c.r, max(c.g, c.b));
            float chroma = most - min(c.r, min(c.g, c.b));
            if (chroma <= 0.0 || most <= 0.0) return c;
            float hue = hueOf(c, most, chroma);
            int s = clamp(int(hue * (360.0 / 6.0)), 0, 360);
            float vividness = chroma / most;
            float light = lightOf(c);
            vec3 m = colorOf(mod(hue + t[mixer + s], 6.0), clamp(vividness * t[mixer + 361 + s], 0.0, 1.0), most);
            float mixedLight = lightOf(m);
            if (mixedLight <= 0.0) return m;
            return m * (sqrt(light / mixedLight) * max(0.0, 1.0 + t[mixer + 722 + s] * vividness));
        }

        float grayOf(vec3 c) {
            float luma = dot(c, LUMA);
            float most = max(c.r, max(c.g, c.b));
            float chroma = most - min(c.r, min(c.g, c.b));
            if (chroma <= 0.0 || most <= 0.0) return luma;
            int s = clamp(int(hueOf(c, most, chroma) * (360.0 / 6.0)), 0, 360);
            return luma * (1.0 + t[mixer + 722 + s] * chroma / most);
        }

        float fleck(int x, int y) {
            uint hash = uint(x) * 374761393u + uint(y) * 668265263u;
            hash = (hash ^ (hash >> 13u)) * 1274126177u;
            hash = hash ^ (hash >> 16u);
            return float(hash & 65535u) / 32767.5 - 1.0;
        }

        float grainAt(int x, int y, float luma) {
            float value;
            if (fleckSize <= 1.0) {
                value = fleck(int(float(x) / fleckSize), int(float(y) / fleckSize));
            } else {
                float across = float(x) / fleckSize;
                float down = float(y) / fleckSize;
                int left = int(across);
                int top = int(down);
                float upper = fleck(left, top) + (fleck(left + 1, top) - fleck(left, top)) * (across - float(left));
                float lower = fleck(left, top + 1) + (fleck(left + 1, top + 1) - fleck(left, top + 1)) * (across - float(left));
                value = upper + (lower - upper) * (down - float(top));
            }
            return value * grainStrength * min(1.0, 0.3 + 0.7 * 4.0 * luma * abs(1.0 - luma));
        }

        int toByte(float value) { return clamp(int(value * 255.0 + 0.5), 0, 255); }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= size.x || p.y >= size.y) return;
            int frameX = frameLeft + p.x;
            int frameY = frameTop + bandTop + p.y;
            #ifdef DISPLAYED
            vec4 shown = texelFetch(displayed, p, 0);
            uvec3 level = uvec3(
                uint(levelOf[int(shown.b * 255.0 + 0.5)]),
                uint(levelOf[int(shown.g * 255.0 + 0.5)]),
                uint(levelOf[int(shown.r * 255.0 + 0.5)])
            );
            #else
            uvec3 level = uvec3(texelFetch(redPlane, p, 0).r, texelFetch(greenPlane, p, 0).r, texelFetch(bluePlane, p, 0).r);
            #endif

            float column = clamp((float(frameX) + 0.5) * float(mapWidth) / float(frameWidth) - 0.5, 0.0, float(mapWidth - 1));
            mapLeft = int(column);
            mapRight = min(mapLeft + 1, mapWidth - 1);
            mapAcross = column - float(mapLeft);
            float down = (float(frameY) + 0.5) / float(frameHeight);
            float row = clamp(down * float(mapHeight) - 0.5, 0.0, float(mapHeight - 1));
            mapUpper = int(row) * mapWidth;
            mapLower = min(int(row) + 1, mapHeight - 1) * mapWidth;
            mapDown = row - float(int(row));

            vec3 rgb = shade(stages[0], level);

            if (localCount > 0) {
                float viewX = placeLeft * turnedWidth + float(frameX) + 0.5 - turnedWidth / 2.0;
                float viewY = placeTop * turnedHeight + float(frameY) + 0.5 - turnedHeight / 2.0;
                float levelX = viewX * cosA + viewY * sinA;
                float levelY = -viewX * sinA + viewY * cosA;
                float scale = 1.0 + tiltAcross * levelX + tiltDown * levelY;
                float tx = mirror * levelX / scale;
                float ty = levelY / scale;
                float wholeX;
                float wholeY;
                if (turns == 1) { wholeX = ty / wholeWidth + 0.5; wholeY = 0.5 - tx / wholeHeight; }
                else if (turns == 2) { wholeX = 0.5 - tx / wholeWidth; wholeY = 0.5 - ty / wholeHeight; }
                else if (turns == 3) { wholeX = 0.5 - ty / wholeWidth; wholeY = tx / wholeHeight + 0.5; }
                else { wholeX = tx / wholeWidth + 0.5; wholeY = ty / wholeHeight + 0.5; }
                float luma = dot(rgb, LUMA);
                for (int i = 0; i < localCount; i++) {
                    float weight = maskWeight(locals[i], wholeX, wholeY) * toneWeight(locals[i], luma) * colourWeight(locals[i], rgb);
                    if (weight <= 0.0) continue;
                    vec3 part = shade(stages[i + 1], level);
                    rgb += (part - rgb) * weight;
                }
            }

            if (monochrome != 0) {
                float gray = mixer >= 0 ? grayOf(max(rgb, vec3(0.0))) : dot(rgb, LUMA);
                rgb = vec3(gray);
            } else if (mixer >= 0) {
                rgb = mixed(max(rgb, vec3(0.0)));
            }

            if (grading >= 0) {
                int s = stepOf(dot(rgb, LUMA), 256);
                rgb += vec3(t[grading + s], t[grading + 257 + s], t[grading + 514 + s]);
            }

            if (vignette >= 0) {
                float centreX = float(frameWidth) / 2.0;
                float centreY = float(frameHeight) / 2.0;
                float fromX = (float(frameX) + 0.5 - centreX) / centreX;
                float fromY = (float(frameY) + 0.5 - centreY) / centreY;
                rgb *= t[vignette + clamp(int((fromX * fromX + fromY * fromY) * 256.0), 0, 512)];
            }

            if (grain != 0) {
                rgb += vec3(grainAt(frameX, frameY, clamp(dot(rgb, LUMA), 0.0, 1.0)));
            }

            // Stored blue first, so that the bytes read back are an ARGB int's.
            imageStore(result, p, vec4(float(toByte(rgb.b)), float(toByte(rgb.g)), float(toByte(rgb.r)), 255.0) / 255.0);
        }
    """.trimIndent()
}
