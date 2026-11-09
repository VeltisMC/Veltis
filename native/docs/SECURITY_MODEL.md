# Security model

The native layer is optional, but loading a shared library executes native
code in the server process. Only install artifacts from a trusted source.

The automatic distribution path:

- verifies the Ed25519 signature over the exact manifest bytes against the
  public key embedded in the application;
- selects the requested `module/platform` entry and requires its conventional
  asset and library names;
- validates archive size and digest, allowlists archive entries, and verifies
  the packaged library digest before installation;
- probes the library format and checks the module's exported identity and ABI
  before use;
- uses the distribution transport's HTTPS/redirect policy and reports a
  failure instead of silently accepting unverified metadata.

The signing private key belongs only in the release secret store. The native
release job is gated on successful signing; local builds must not fabricate
release metadata or replace the trusted key.

A manually configured native library path is an explicit trust decision by the
server operator. The Java fallback remains available when native loading is
disabled or rejected.
