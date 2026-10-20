# Writes linear-192-380-264.jxl, the samples of SyntheticDng.linear(192, 380, 264) with a gradient in the active
# area's top left corner, losslessly in JPEG XL as a Galaxy's Expert RAW stores them. Needs numpy and imagecodecs.
import sys, numpy as np, imagecodecs
W, H = 32, 24
top, left, bottom, right = 2, 2, 22, 30
levels = [192 + 64, 380 + 72, 264 + 80]
a = np.zeros((H, W, 3), dtype=np.uint16)
for c in range(3):
    a[top:bottom, left:right, c] = levels[c]
for y in range(top, top + 6):
    for x in range(left, left + 6):
        a[y, x] = [levels[0] + 10 * x, levels[1] + 7 * y, levels[2] + x * y]
data = imagecodecs.jpegxl_encode(a, lossless=True, bitspersample=16)
assert (imagecodecs.jpegxl_decode(data) == a).all()
open(sys.argv[1], 'wb').write(data)
