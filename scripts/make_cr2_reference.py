#!/usr/bin/env python3
"""Make an external linear-RGB oracle using rawpy/LibRaw, not Latent's decoder.

This is an optional developer tool, not an app dependency. Install rawpy and NumPy
in a temporary Python environment. Source photos and reference files stay outside Git.
See docs/canon-cr2-investigation.md for the two CC0 samples and exact arguments.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

import numpy as np
import rawpy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--black-level", type=int, default=2048)
    parser.add_argument("--white-level", type=int, required=True)
    parser.add_argument("--matrix", type=float, nargs=9, required=True)
    parser.add_argument("--crop", type=int, nargs=4, default=[168, 56, 5184, 3456],
                        metavar=("LEFT", "TOP", "WIDTH", "HEIGHT"))
    args = parser.parse_args()
    if not 0 <= args.black_level < args.white_level <= 16383:
        parser.error("Expected a valid 14-bit black/white range")
    matrix = np.array(args.matrix, dtype=np.float64).reshape(3, 3)
    if not np.isfinite(matrix).all() or not np.allclose(matrix.sum(axis=1), 1, atol=1e-5):
        parser.error("The matrix must be finite and map neutral to neutral")
    left, top, width, height = args.crop
    with rawpy.imread(str(args.source)) as raw:
        sizes = raw.sizes
        if (left < sizes.left_margin or top < sizes.top_margin or width <= 0 or height <= 0
                or left + width > sizes.left_margin + sizes.width
                or top + height > sizes.top_margin + sizes.height
                or any(v % 2 for v in (left - sizes.left_margin, top - sizes.top_margin, width, height))):
            parser.error("The crop must contain complete 2x2 cells within LibRaw's visible area")
        wb = np.asarray(raw.camera_whitebalance, dtype=np.float64)
        if wb.shape != (4,) or not np.isfinite(wb).all() or np.any(wb <= 0) or wb[1] != wb[3]:
            parser.error("Expected positive as-shot gains with equal greens")
        gains = wb[:3] / wb[1]
        native = raw.postprocess(
            half_size=True, user_wb=wb.tolist(), use_camera_wb=False, use_auto_wb=False,
            output_color=rawpy.ColorSpace.raw, output_bps=16, user_flip=0,
            user_black=args.black_level, user_cblack=[0, 0, 0, 0],
            # LibRaw's user_sat override is applied after black subtraction. Passing the
            # absolute white level here would add an unintended brightness scale.
            user_sat=args.white_level - args.black_level,
            no_auto_bright=True, adjust_maximum_thr=0, gamma=(1, 1),
        )
        x = (left - sizes.left_margin) // 2
        y = (top - sizes.top_margin) // 2
        native = native[y:y + height // 2, x:x + width // 2].astype(np.float64) / 65535
    linear = np.clip(native @ matrix.T, 0, 1)
    pixels = np.floor(linear * 16383 + 0.5).astype("<u2")
    digest = hashlib.sha256(args.source.read_bytes()).digest()
    header = b"LCR2REF1" + digest + struct.pack("<4I12f", width // 2, height // 2,
                args.black_level, args.white_level, *gains, *args.matrix)
    with args.output.open("wb") as output:
        output.write(header)
        output.write(pixels.tobytes())
    metadata = dict(source_sha256=digest.hex(), rawpy=rawpy.__version__, libraw=rawpy.libraw_version,
                    crop=args.crop, black=args.black_level, white=args.white_level,
                    white_balance=gains.tolist(), matrix=args.matrix)
    args.output.with_suffix(args.output.suffix + ".json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata))


if __name__ == "__main__":
    main()
