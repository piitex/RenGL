# Contributing to RenGL

Bug reports, fixes and documentation corrections are welcome. RenGL is a personal project, so replies and reviews come
when I have time, and I can't promise a pull request will be merged.

## Reporting a bug

Open an issue and include:

- what you did, what you expected and what happened instead (a stack trace or log if there is one);
- your operating system, CPU architecture, GPU and driver, and JDK version;
- which rendering API you were using (Metal, OpenGL or Vulkan);
- the smallest code that shows it, if you can.

Many problems depend on the GPU and driver, so those details matter. Parts of RenGL have never run on some platforms
(see [known limitations](docs/reference/known-limitations.md) and the status notes in the README); a report from one of
those is useful even if it is only "this fails here".

For a security problem, don't open a public issue. Follow [SECURITY.md](SECURITY.md).

## Suggesting a feature

Open an issue first and describe what you want to do and why. RenGL keeps a small footprint, so some things will be
declined: no bundled browser engines, and the whole thing is meant to stay well under 100 MB.

## Sending a pull request

For anything larger than a small fix, please open an issue first so we agree on the approach before you spend the time.

1. Fork the repository and branch from `main`.
2. Build and run the tests (JDK 25 is required; the Maven wrapper is included):

   ```bash
   ./mvnw verify
   ```

   Use `mvnw.cmd` on Windows. The GPU tests skip themselves on a machine with no usable GPU, which is what happens on
   the CI runner, so run them locally if you changed rendering code.
3. Add or update a test when you fix a bug or add behavior, and update the matching page under [docs](docs) when the
   change is visible to users.
4. Keep the pull request to one thing. Match the style of the code around your change.
5. Write short commit messages that say what changed.

The CI build runs `./mvnw verify` on Windows. A pull request needs it to pass before it can be merged.

## License of your contribution

RenGL is under the [RSGL 1.0 license](LICENSE). By submitting a contribution you agree to
[section 8 of that license](LICENSE): you keep ownership of what you wrote, and you grant the Licensor (Piitex) a
perpetual, worldwide, irrevocable, non-exclusive, royalty-free license to use, modify, sublicense and distribute it under
the license or any other license. You also confirm you have the right to do so, so don't submit code you copied from
somewhere with an incompatible license, or work your employer owns.

If you add a third-party library or a bundled asset (a font, an image, a sound), say where it came from and under what
license in the pull request, and add it to [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
