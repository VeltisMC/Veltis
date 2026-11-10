# Platform support

`NativeLoader` currently recognizes these OS/architecture keys:

| Operating system | Architectures |
| --- | --- |
| Windows | `x86_64`, `aarch64` |
| Linux | `x86_64`, `aarch64` |
| macOS | `x86_64`, `aarch64` |

Recognition means the loader can name and inspect the expected library format;
it does not mean that a native artifact has been built, run, or published for
that platform. Cross-compilation is not runtime verification. Check the
workflow and release manifest for current build and release evidence.

Unrecognized platforms receive an explicit unavailable status and retain the
Java implementation.
