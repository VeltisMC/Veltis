# CI support matrix

The checked-in workflow at `.github/workflows/gradle.yml` is authoritative for
the current host, cross-compilation, Java-only, packaging, and release jobs.
This document intentionally does not duplicate runner names or claim results:
workflow configuration is not evidence that a job has completed successfully.

Interpret results by evidence type:

- A successful host build and CTest run verifies that runner's native build.
- A successful cross-build verifies compilation and configured artifact checks,
  not execution on the target.
- A signed manifest records the artifacts that were packaged and verified.
- Native release publication is skipped unless the signing step produced a
  signature.

No CI run or release publication was performed as part of the current code
changes.
