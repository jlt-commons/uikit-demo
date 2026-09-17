# Examples

Every example follows the same shape: require only `demo.registry`,
never `demo.core` (the dependency points the other way), `defmethod`
its action specs and handlers right next to the feature they serve, and
call `register-example!` once at the end. The hub picks it up through a
single `require` line. See [Architecture](architecture.md) for the
registry pattern this rests on.

## Counter

The first example, and it used to *be* the main window before the hub
arrived. The shape is [Replicant](https://replicant.fun)'s counter,
rendered through AppKit: its own state atom, a pure view, actions as
data. It's also the hello-world of
[7GUIs](https://eugenkiss.github.io/7guis/tasks/#counter), whose
Counter task glitter-uikit's own upstream examples implement too.
Comparing the two versions shows what the hub's registry actually buys:
upstream's bootstraps its own app loop, and this one is just a view.

## Currency Converter

A port of the classic from O'Reilly's *Learning Cocoa*
([chapter 7](https://www.oreilly.com/library/view/learning-cocoa/0596001606/ch07.html),
2001), faithful on purpose, including the explicit Convert button
rather than converting on every keystroke. It's a 25-years-later diff
of the same program:

| Learning Cocoa (2001) | This notebook |
|---|---|
| `Converter` model class | `convert`, one pure function |
| `ConverterController` plus outlets | a state atom and a pure view |
| Interface Builder nib | `[:vbox ...]` data |
| target/action wiring | `:on {:click [[:action.currency/convert]]}` |

The whole `ConverterController`, the class the book spends a chapter
wiring, dissolves into three `defmethod`s on the shared dispatcher.
That dissolution is the point, in the spirit of Brian Marick's
[*Functional Programming for the Object-Oriented Programmer*](https://leanpub.com/fp-oo):
take a program once wired by hand and watch the ceremony become data.

The nib row of that table hides a sharper point. Interface Builder
never generated code: a classic `.nib` is a binary archive of
freeze-dried objects, and the XML `.xib` that replaced it (Xcode 3
onward) made the same object graph into diffable text.
[Cappuccino](https://www.cappuccino.dev/) built a real feature on that
fact, converting `.nib`s into `.cib`s that
[Objective-J](https://github.com/cappuccino/cappuccino) rehydrated
inside a browser. The difference here isn't data versus code; it's
*which* data. A nib serializes constructed objects, and hiccup
describes intent and lets the reconciler do the constructing. Same
philosophy, one level earlier.

Two renderer details came from this port, both inherited from
glitter-uikit's own upstream Temperature Converter example (its
closest cousin, and its docstring is worth reading for the
measured-not-guessed renderer findings it documents): `:entry` widgets
need `:width-request`, not `:width-chars`, to control their width on
AppKit, since `:width-chars` there is a text-wrapping hint rather than
a size constraint, and entry text arrives nested under
`:glitter/dom-event` rather than at the top level of the event map (see
[Architecture](architecture.md) for the fix, `demo.registry/event-value`).
That nesting was this notebook's first shipped bug: every keystroke's
handler read the wrong key, got `nil`, and Convert dutifully computed
nothing.

## Live Currency

The Currency Converter, grown up: pick two currencies from drop-downs,
type an amount, and the app fetches the real rate and converts for
you, no Convert button and no manual rate entry. Rates come from
[Frankfurter](https://frankfurter.dev), a keyless API over the ECB's
daily reference rates, fetched with
[jolt-lang/http-client](https://github.com/jolt-lang/http-client)
(clj-http-lite running on jolt host shims: BSD sockets and OpenSSL
through `jolt.ffi`, no JVM anywhere in the path).

A few findings shaped the final code, all verified in a scratch project
first:

- **HTTPS just works** under jolt: `(http/get url)` over TLS returns an
  ordinary response map, nothing special required.
- **`:as :json` returns a string, not parsed data.** clj-http-lite's
  JSON coercion needs a JSON library on the classpath, and jolt ships
  none by default, so the body arrives raw. The first version regexed
  the one number it needed out of that string, which worked but drew
  the obvious pushback: use `clojure.data.json`, it's pure Clojure and
  has no reason not to run under jolt. Trying that produced one of
  jolt's clearer error messages: `data.json`'s date writers touch
  `java.time.format.DateTimeFormatter`, which [RFC 0008](https://jolt-lang.github.io/docs/rfc/0008)
  moves out of jolt core, and the load error names the exact library
  that supplies it. One dependency later (see
  [Dependencies](dependencies.md)), `read-str` works verbatim.
- **`future` works, but fibers are the better tool.** The first version
  used a plain `future`, which runs fine under jolt on a real OS
  thread. But a bare `future` is debatable idiom (the
  [community style guide](https://guide.clojure.style/) leans toward
  more explicit concurrency tools, and a never-dereferenced future has
  a habit of swallowing exceptions), and looking for the alternative
  surfaced something worth knowing: jolt's `core.async` runs on a fiber
  runtime, Chez continuations over a preemptively scheduled carrier
  pool. `async/io-thread` runs its body on a fiber, and IO the runtime
  can actually see, channel operations and the socket reads
  http-client's net layer is built on, *parks* that fiber and frees its
  carrier rather than blocking a thread. A thousand in-flight fetches
  cost a thousand parked stacks, not a thousand OS threads. The one
  caveat: IO the runtime can't see, a blocking FFI call or
  `Thread/sleep`, still pins the carrier, and `async/thread` is the
  right escape for those instead. Either way, the result re-enters
  through the normal action pipeline, and a failure comes back as an
  explicit `:action.fx/rate-failed` through a real try/catch, never
  silently dropped.
- **Async introduces a race, with the usual fix.** Changing currencies
  while a fetch is in flight must not let the stale response win. Every
  request is tagged with the currency pair it's answering, and
  `:action.fx/rate-arrived` drops its result unless that pair still
  matches the current selection.

Two shell details round it out: `:drop-down` takes `:items` (strings)
and `:selected` (an index), and its `:selected-changed` event carries
the new index, `nil` when nothing is selected, so both handlers are
`when-let` guarded. And this example is what added the registry's
`:on-open` hook: it runs on every open *and* reopen of an example
window, so Live Currency refetches its rate each time its window comes
back to the front.

## Particle Toy

A physics playground: click the canvas to burst particles, watch
gravity and wall bounces settle them, seed or clear the field with two
buttons.

`:canvas` is an `NSButton` hosting CALayer circles, driven by a
`:circles` data prop. A `CALayer` takes no part in hit-testing, so
every click always lands on the canvas itself rather than being
swallowed by a sub-view. Animation runs through `w/every!`, which wraps
an `NSTimer` scheduled on the main run loop, so each tick can `swap!`
particle positions directly and let the reconciler sync the layers.
Physics itself is pure Clojure over a vector of
`{:x :y :vx :vy :r}` maps, gravity and wall bounces with damping,
specced like everything else in the app.

Timer lifecycle turned out to be the real design work. The window needs
to start its timer on first open and stop it when the window closes,
and the shell had no window-close awareness to hook into. The
alternative to building an `NSWindowDelegate` just for
`windowWillClose:` is polling: since the tick is already running every
frame, it also asks the window whether it's still visible and cancels
itself the moment the answer is no. Reopening the window restarts the
timer through the same `:on-open` hook that Live Currency uses to
refetch rates, which is why that hook carries a window-pointer
argument at all.

One FFI wrinkle showed up in that visibility check: a `BOOL` return
crosses jolt's FFI as a Scheme *character*, not an integer, so the
check has to compare against `(char 0)` rather than `0`. This is the
same fact that bit glitter-uikit's own
`applicationShouldTerminate...` callback upstream.

Physics taught its own lesson, the hard way. The first launch had
gravity written for AppKit's unflipped, bottom-left-origin coordinate
system, and the particles fell *up*. The canvas's actual layer tree
uses top-left, y-grows-downward geometry, so gravity has to *increase*
y, not decrease it. One measured launch beat what the docs implied.
