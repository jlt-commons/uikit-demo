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
2. **A pin's own `:jolt/native` declarations become this project's
   problem too**, whether or not this project's code ever calls into
   them. This bit for real: see "The GTK4 requirement that shouldn't
   have existed" below for the fix, once this project actually hit it.

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

## The GTK4 requirement that shouldn't have existed

This project used to pin `io.github.jlt-commons/glitter` directly, for
one reason only: `demo.core`/`demo.registry` need `glitter.core` and
`glitter.alias`. But `glitter`'s own `deps.edn` declares GTK4/GLib/
GObject/GIO under top-level `:jolt/native`, and jolt inherits a
dependency's declared natives transitively, hard-failing in
`load-natives!` before any namespace loads if one is missing. So this
demo — pure AppKit, zero GTK calls anywhere — needed GTK4 installed
just to boot. An `:aliases`-scoped `:jolt/native` doesn't help either;
it's silently ignored (verified live, same finding `glitter-uikit`'s
own `deps.edn` records).

Fixed 2026-09-17 by extracting the toolkit-agnostic two-thirds of
`glitter` (`glitter.core`, `glitter.protocols`, `glitter.alias`, and
friends — no `:jolt/native` anywhere in its own `deps.edn`) into a
standalone [glitter-core](https://github.com/jlt-commons/glitter-core)
repo. Proposed and tracked at
[jlt-commons/meta#1](https://github.com/jlt-commons/meta/issues/1).
This project now pins `glitter-core` directly instead of `glitter`,
and `glitter-uikit` (a dependency of this project too) did the same —
so `glitter` is no longer anywhere in this project's dependency graph
at all. Verified: `(find-ns 'glitter.gtk)` returns `nil` under the new
pins, and `jolt path` shows no `glitter` gitlib path whatsoever.

Three more pin churns followed the same day, each for the reason
`:jolt/min-version`'s own header comment predicts — a top-level
coordinate overriding a transitive one means everyone's pin has to
track everyone else's:

- `glitter-core` had no commit pushed to its GitHub repo yet at first
  (a private, brand-new repo), so its pin briefly used `git@` (SSH)
  instead of this file's usual `https://` form — jolt's git fetch
  can't do an anonymous `https://` clone of a private repo. Switched
  back to `https://` once `glitter-core` went public.
- `glitter-core` later raised its own `:jolt/min-version` from
  `0.7.24` to `0.8.0` (for family-wide consistency, not because
  `glitter-core` itself needs it — it has zero `jolt.ffi` usage).
  `glitter-uikit`'s own `glitter-core` pin needed bumping to pick that
  up, and this project's pin needed bumping to match `glitter-uikit`'s
  new commit in turn.
- `glitter-uikit`'s history got rewritten (an unrelated commit-message
  cleanup), which orphaned every SHA on its old `main` — including the
  one this project had just pinned. Re-pinned to the equivalent
  commit on the rewritten history.

The lesson isn't really about GTK4. It's that pinning a coordinate
this project doesn't use directly (`glitter-core`, `nexus-jolt`) still
means tracking that coordinate's own upstream churn, because jolt's
breadth-first, top-level-wins resolution means whatever THIS project
declares is what actually gets built, regardless of what any
transitive dependency itself pins.
