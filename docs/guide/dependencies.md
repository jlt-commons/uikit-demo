# Dependencies

Every dependency here is a pinned git SHA, and every pin has a reason.
The two rules worth carrying into any other jolt project:

1. **A transitive coordinate's pin must match what its dependent
   itself pins.** Jolt's dependency walk is breadth-first, so a
   top-level coordinate silently overrides the transitive one. Pinning
   glitter to a newer SHA than glitter-uikit was built against would
   quietly override what glitter-uikit was actually tested with. This
   also means the coordinate *name*, not just the SHA, has to match:
   when glitter-uikit moved its own glitter pin from the `burinc` org
   to `jlt-commons` (GitHub redirects the old URLs, so nothing broke
   outright), this project's own `io.github.burinc/glitter` had to be
   renamed too, or `jolt -Stree` would have resolved two separate
   copies side by side instead of one shared one.
2. **GTK4 must be installed** (`brew install gtk4`) even though this
   demo renders pure AppKit. glitter's own `deps.edn` declares GTK
   natives under `:jolt/native`, jolt inherits a dependency's natives
   transitively, and it hard-fails before any namespace loads at all
   when one is missing.

`org.clojure/spec.alpha` needs an explicit declaration here that it
wouldn't need on the JVM. Jolt *is* Clojure, so `org.clojure/clojure`
is a terminal dependency under jolt: it contributes neither an artifact
nor children, which means it never drags in `spec.alpha` transitively
the way the JVM artifact does. Declared explicitly, it behaves like any
ordinary pure-Clojure dependency; jolt's dependency resolver handles
Maven coordinates right alongside git ones.

## The jolt 0.8.0 upgrade: the write-order break

[jolt 0.8.0](https://github.com/jolt-lang/jolt/blob/main/CHANGELOG.md#080---2026-08-31)
made `jolt.ffi` name-for-name compatible with `babashka.ffi`, and two
of its changes break existing callers silently. A fixed array in a
layout became `[:array type count]` instead of `[:array count type]`,
which raises at compile time, so it's self-announcing. `ffi/write`
started taking its value *before* the offset,
`(write p t v offset)` where it used to be `(write p t offset v)`, and
that one can't announce itself: an offset and a value are both
integers, so an old call site on the new runtime writes the right kind
of value to the wrong place and says nothing.

This project's own FFI surface, three `defcfn` bindings plus
`ffi/null` (see [AppKit integration](appkit-integration.md)), had
nothing to change. Its *dependencies* did. Auditing every pinned git
dependency under `~/.jolt/gitlibs` against jolt 0.8.0 with the old pins
in place found real damage:

- **glitter-uikit**, at its old pin, builds a `CFRunLoopSourceContext`
  as nine zeroed offsets and a `perform` function pointer at offset 72,
  the old argument order. Under 0.8.0, that call reads as "write 72 at
  offset *perform*," a function pointer interpreted as a byte offset,
  and requiring `demo.core` died at load with an invalid memory
  reference. The demo never started at all.
- **jolt-lang/http-client**, at its old pin, fills `addrinfo` hints,
  `pollfd`, `timeval`, and `z_stream` structs by offset in the same old
  order. The hints struct's `ai_socktype` field landed in the wrong
  bytes, `getaddrinfo` failed, and Live Currency's fetch threw before a
  socket ever opened. It would have said "Rate fetch failed" forever,
  with nothing in the error to point at the real cause.
- **glitter** was unaffected: its own FFI surface is GTK, never loaded
  by this demo since `glitter.core` doesn't require it, and it carries
  no raw writes of its own regardless.
- **jolt-crypto**, pulled in transitively through http-client, had two
  old-order writes in its DER encode/decode path, but http-client only
  takes `SecureRandom` from it, a path this demo never exercises.
  Upstream had already fixed it the same day; http-client's own pin at
  the time simply hadn't caught up yet.

Upstream had fixed both real breakages before this project even hit
them, one commit per repo, titled the same way in both:
"Move ffi/write's value argument before the offset." So the fix here
was two pin bumps, not a fork: glitter-uikit to the commit carrying
that fix plus its own CI and docs updates, and http-client to the
commit carrying the fix and nothing else. Both dependencies now also
declare `:jolt/min-version "0.8.0"`, and so does this project's own
`deps.edn`: the old and new `ffi/write` argument orders can't be told
apart at runtime, so a jolt older than 0.8.0 has to refuse loading
outright rather than silently write to the wrong memory. The floor
only bites from 0.8.0 onward, since an older runtime simply ignores a
`:jolt/min-version` key it doesn't recognize, so it can't guard against
this exact break. What actually guards this one is the pins themselves.

Verification after the bump ran the whole path that the old argument
order corrupted: a headless load printing all four example ids, a real
`(http/get ...)` against Frankfurter answering 200 with a rate, and a
scripted `app/run` mounting the hub, draining a thunk through the
`CFRunLoopSourceContext` scheduler, opening Live Currency, and watching
its status move from `:loading` to `:ok` with a real rate in roughly
350 milliseconds, three times over as the currency selection changed.

## Two more pins, found the same way

Later jolt releases kept tightening what the compiler will accept, and
two more dependencies needed bumping for reasons this project's own
code never touched directly.

`jolt-lang/time` supplies the `java.time` classes that `clojure/data.json`
needs for its date writers ([RFC 0008](https://jolt-lang.github.io/docs/rfc/0008)
moves those classes out of jolt core). A dependency has to *declare*
which classes it provides, per
[RFC 0014](https://jolt-lang.github.io/docs/rfc/0014), and this
project's pin predated the commit that added that declaration. jolt
could see the dependency was present but not what it provided, and
refused to load `DateTimeFormatter` with the same "no dependency
provides" error as if `jolt-lang/time` weren't declared at all. The
fix was a pin bump to the commit that adds the `:jolt/provides` map.

`jolt-lang/http-client`'s connect-retry loop had a `recur` inside a
`try` whose enclosing `loop` sat outside that `try`, a shape Clojure
has always refused (a `recur` can't cross a `try` boundary) but which
an older jolt compiled anyway, into a loop that silently rebound
nothing. jolt refuses it too as of 0.8.2, so the walk stopped building
at the old pin. This one was already fixed upstream by the time it
surfaced here: the fix moves the retry decision out of the `try` and
issues the `recur` after it returns, so nothing about the connect
logic's actual behavior changes.

The pattern behind both: pin bumps aren't only about picking up new
features. On a fast-moving compiler, an old pin can simply stop being
buildable, and the fix is almost always already sitting on the
dependency's own default branch.
