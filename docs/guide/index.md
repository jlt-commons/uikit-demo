# Guide

uikit-demo drives [glitter-uikit](https://github.com/jlt-commons/glitter-uikit),
the native macOS (AppKit) renderer for
[glitter](https://github.com/jlt-commons/glitter), a Replicant-style
reconciler for Clojure (well, [Jolt](https://github.com/jolt-lang/jolt)).
If you come from the web, think of glitter as the reconciler and AppKit
as the DOM: hiccup goes in, real `NSButton`s come out.

Start here, then follow the link that matches what you're trying to do.

- [Architecture](architecture.md): the four-layer namespace split, the
  registry pattern that lets each example plug into the hub without a
  central `case` statement, and the state/view/action data flow the
  whole app follows.
- [AppKit integration](appkit-integration.md): the parts that aren't
  reconciled, the menu bar, the Preferences window, and how each example
  opens in its own cached window near the pointer.
- [Dependencies](dependencies.md): why the git pins are exactly what
  they are, including the jolt 0.8.0 upgrade that broke two of them in
  a way nothing at compile time could catch.
- [Bundling a `.app`](bundling.md): turning the project into a real
  macOS app bundle, and the LaunchServices error that only a Mach-O
  executable can satisfy.
- [Examples](examples.md): what each of the four demo windows shows and
  the bug each one taught us about.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.0 or newer
- `brew bundle` installs the rest: Graphviz (renders the dataflow
  diagram) and ffmpeg (converts the screen recording into an inline
  GIF)

## Running

```bash
brew bundle
jolt demo     # run the app
jolt bundle   # AOT-compile it into Demo.app
jolt app      # open Demo.app
```
