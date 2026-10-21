# jxlatte

A JPEG XL decoder written in plain Java by Leo Izen, under the MIT licence (`LICENSE`). Latent uses it for the DNGs
a Galaxy writes in Expert RAW, whose samples are compressed with JPEG XL.

- Upstream: <https://github.com/Traneptora/jxlatte>, commit `b98e86eb6a13baca5967dc25cab7698ac6d14675`
  (2026-08-23). It is not published to Maven, so its sources are kept here.
- Changes: [`latent.patch`](latent.patch), against that commit. They only let go of memory a frame no longer needs:
  the weighted predictor's state once a channel is decoded, each group's entropy stream (with its 4 MiB LZ77
  window) once the group is, each modular group once it is copied into the frame, and the modular copy of the
  whole picture once the frame holds it. A 4000x3000 Expert RAW picture needed more than 1.2 GB of heap, and now
  decodes in 384 MB, to the same samples (checked against upstream on the whole picture).
  The other change is for a file cut short: upstream reads a frame's table of contents on a thread of its own, and
  when that failed the decoder waited for ever for the groups that never came. The readers still waited for are now
  told it failed, so the decoder throws the `EOFException` (`JpegXlTest`, with a time limit, keeps it so).

To update: check out a newer upstream commit, apply `latent.patch`, copy its `java/` folder to `src/main/java`, and
note the commit here.
