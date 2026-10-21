# Writes groups.jxl: a 600x520 picture of three 16-bit samples a pixel, losslessly in JPEG XL, large enough to be
# stored in several groups (each is a table of contents entry), for a test that cuts it short. Needs numpy and imagecodecs.
import sys, numpy as np, imagecodecs
H, W = 520, 600
y, x = np.mgrid[0:H, 0:W]
a = np.stack([x * 5 + y * 3, x * 2 + y * 7 + (x // 8) * (y // 8) % 50, (x // 16 + y // 16) * 40], axis=-1).astype(np.uint16)
data = imagecodecs.jpegxl_encode(a, lossless=True, bitspersample=16)
assert (imagecodecs.jpegxl_decode(data) == a).all()
open(sys.argv[1], 'wb').write(data)
