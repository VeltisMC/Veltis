# Security model

The native layer is optional, but loading a shared library executes native
code in the server process. Only install artifacts from a trusted source.

The automatic distribution path:

- fetches the manifest only over HTTPS from the official GitHub repository the
  runtime is configured with;
- selects the requested `module/platform` entry and requires its conventional
  asset and library names;
- validates archive size and SHA-256 against the manifest before extracting or
  installing anything, allowlists archive entries, and verifies the packaged
  library digest before installation;
- probes the library format and checks the module's exported identity and ABI
  before use;
- uses the distribution transport's HTTPS/redirect policy and reports a
  failure instead of silently accepting unverified metadata.

The manifest is not digitally signed. SHA-256 proves an archive matches the
manifest that was served, not who published that manifest; publisher
authenticity depends on the trusted HTTPS source and official repository
configuration. No signing key or secret is involved in publication or
downloads, and local builds must not fabricate release metadata.

A manually configured native library path is an explicit trust decision by the
server operator. The Java fallback remains available when native loading is
disabled or rejected.
