# Bundling a `.app`

A macOS app bundle is nothing but a folder convention: `Demo.app/Contents/`
holding an `Info.plist` and an executable under `MacOS/`. `jolt bundle`
builds that folder, and `jolt app` (or double-clicking `Demo.app` in
Finder) launches it.

## What the bundle buys over `jolt demo`

- `CFBundleName` comes from `Info.plist` the supported way, so the app
  menu says "Demo" without the mutable-info-dictionary hack described in
  [AppKit integration](appkit-integration.md). That hack stays in the
  code anyway, both for plain `jolt demo` runs and because it's a no-op
  once the plist already supplies the name: it just rewrites the same
  value.
- A Dock name and LaunchServices identity (`CFBundleIdentifier`),
  Spotlight visibility, and a place to hang an icon later
  (`Resources/` plus `CFBundleIconFile`).

## Three gotchas from the "why is my app a hollow shell" investigation

1. LaunchServices launches a bundle with `cwd /` and launchd's minimal
   `PATH`, `/usr/bin:/bin:/usr/sbin:/sbin`, no Homebrew anywhere in it.
   An early launcher had to prepend the Homebrew bin directories and
   `cd` back to the project root before doing anything else.
2. The plist can't carry an org-mode-style breadcrumb comment. A
   comment would land before the `<?xml?>` declaration, which has to be
   the first bytes of the file, and anything ahead of it produces a
   plist Property List Editor and `plutil -lint` both reject.
3. `stdout` from a Finder launch goes to the unified log, not a
   terminal. `println` output shows up in Console.app, or via
   `log stream --process demo`, not nowhere, but also not where a
   `jolt demo` run would show it.

A locally built bundle carries no quarantine attribute, so Gatekeeper
leaves it alone. Distributing it to anyone else is a separate problem:
codesigning and notarization only enter the picture once the bundle
leaves this machine.

## The -10669 saga

The first launcher was a shell script. `jolt app` on that bundle failed
with:

```
_LSOpenURLsWithCompletionHandler() failed with error -10669.
```

The script itself was fine; running
`Demo.app/Contents/MacOS/demo` directly launched the app without
complaint. Only LaunchServices refused it. The usual suspects all came
up empty: no quarantine extended attribute, `plutil -lint` passed,
`lsregister -f` changed nothing, an ad-hoc `codesign --deep -s -` changed
nothing. -10669 isn't even in the public headers; `LSConstants.h`'s
error enum stops at -10667, `internal-error`. (Internet folklore blames
Rosetta, but a shell script has no architecture, so that explanation
never fit the symptom.)

A controlled experiment settled it: two bundles, identical `Info.plist`,
differing only in the executable.

```bash
# MachoTest.app/Contents/MacOS/machotest: compiled from `int main(){return 0;}`
# ScriptTest.app/Contents/MacOS/scripttest: `#!/bin/sh` + `exit 0`, chmod +x
open MachoTest.app    # opens, exit 0
open ScriptTest.app   # _LSOpenURLsWithCompletionHandler() failed with error -10669
```

This macOS build refuses to launch a bundle whose `CFBundleExecutable`
is a script; it wants a real Mach-O. (clang ad-hoc signs automatically
on Apple Silicon, and the experiment shows that's sufficient. No
Developer ID is needed for a local launch.)

The fix that followed was a ten-line C launcher doing exactly what the
script did: resolve its own location, `chdir` to the project root, fix
`PATH`, and `exec jolt`. One nuance survived the rewrite: after the
`exec`, the process's executable is `jolt`, so in-process
`CFBundleGetMainBundle` no longer points back at `Demo.app`.
LaunchServices still keeps the Dock identity, since it tracks the
process rather than the executable path, and `set-app-name!` from
[AppKit integration](appkit-integration.md) keeps the menu title
correct regardless.

## The launcher dissolves

The C shim lasted one session. Jolt runs on Chez Scheme, and Chez isn't
a C-emitting compiler: it compiles to native code directly, which is
part of why jolt starts as fast as it does. The only C in the whole
picture is Chez's own kernel, `libkernel.a`, which `jolt build` links
against, and that observation pointed at a better answer than a
launcher written in any language: no launcher at all.

```
jolt build -m demo.core -o Demo.app/Contents/MacOS/demo
```

`jolt build` ahead-of-time compiles the runtime, the standard library,
every dependency, and the app itself into one self-contained Mach-O,
and that binary simply *is* `CFBundleExecutable`. Everything the C
shim existed to do dissolves along with it: there's no `PATH` to fix,
because nothing needs to find `jolt` anymore; there's no `chdir` to the
project root, because nothing is read from the project at runtime; and
the executable-identity split goes away too, since the process's
executable now genuinely is the bundle's, so `CFBundleGetMainBundle`
points at `Demo.app` and `Info.plist`'s `CFBundleName` titles the menu
the fully supported way. LaunchServices is satisfied for the same
reason the -10669 saga cared about in the first place: the executable
is a real Mach-O.

The trade is that the binary is now a snapshot. `jolt bundle` reruns
the whole build, so `jolt demo` stays the live path for day-to-day
development. The C launcher's shape is worth remembering anyway: ten
lines of `_NSGetExecutablePath`, `chdir`, and `execlp` is exactly what
a bundle needs if it ever has to launch something it can't link in
directly.
