# Security policy

## Supported versions

RenGL is at version 0.1.1. Only the latest release gets security fixes.

## Reporting a vulnerability

Please do not report a security problem in a public issue.

Use GitHub's private reporting instead: open
<https://github.com/piitex/RenGL/security/advisories/new> (or the **Security** tab, then
**Report a vulnerability**). Only the maintainer can see the report.

If that page is not available, open an issue that says you have a security report and gives no
details, and the maintainer will arrange a private way to send it.

Please include the RenGL version, your operating system and Java version, and the steps to
reproduce the problem.

This is a one-person project, so there is no guaranteed response time. Reports are read and
answered as soon as the maintainer can.

## What counts

Problems in RenGL's own code, for example unsafe handling of a file, archive or URL that a
program passes to RenGL, or a flaw in the packager or the downloads module.

Problems in the libraries RenGL depends on (LWJGL, Skiko and the others in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)) should be reported to those projects. If RenGL
needs to update its dependency because of one, an issue here is welcome.
