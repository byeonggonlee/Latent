# Security

Latent keeps everything on the phone and has no server, so the risks are in what the app does with files
and permissions on the phone itself: for example reading a photo, a raw file (Sony ARW, DNG with its JPEG, JPEG XL
or lossless JPEG samples), an `.xmp` preset, a `.cube` file or a backup file that someone crafted, taking a file
name from a camera over USB, or leaking a recorded location.

In scope: the app and its build files in this repository, including the JPEG XL decoder kept in `third_party/jxlatte`
(with the changes in its `latent.patch`). A fault in the original jxlatte is best reported to its author as well.

## Reporting a problem

Please do not open a public issue for a vulnerability. Use GitHub's private reporting instead: the
[Security tab](https://github.com/byeonggonlee/Latent/security/advisories/new) of this repository, "Report a
vulnerability". Say what you did, what happened, and the version from Library menu → About Latent.

This is a project of one person: expect an answer within a couple of weeks, not days. A fix comes out as the
next nightly and the next release.

## Supported versions

Only the newest release and the nightlies after it.
