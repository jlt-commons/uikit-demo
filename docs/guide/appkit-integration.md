# AppKit integration

glitter-uikit's reconciler owns only a window's *content view*, so the
menu bar, the Preferences window, and how a second window opens are all
territory the app claims directly, in plain `objc_msgSend` calls.
Owning the titlebar this way cuts both ways: there's no declarative
titlebar widget set, but there's also nothing to fight when the
declarative model doesn't fit.

## The app menu's title

An unbundled process (which `jolt demo` is: no `.app`, no `Info.plist`)
gets a menu bar with one bold entry named after the process, so it
reads "jolt" instead of the app's name. AppKit ignores whatever title
you set on the menu object itself; the bar draws the bundle's
`CFBundleName`, falling back to the process name when there is no
bundle.

The workaround: `CFBundleGetInfoDictionary` on the main bundle returns
a live, mutable dictionary, and seeding `CFBundleName` into it *before*
`NSApplication` initializes makes AppKit read that name instead.
`NSString*` and `CFStringRef` are toll-free bridged, so glitter-uikit's
own `nsstring` helper works as a CF key or value with no extra
conversion. This is a well-worn hack, not documented API, and the real
fix is a proper `Info.plist` (see [Bundling a .app](bundling.md)). The
hack stays anyway, so a plain `jolt demo` run during development also
gets the right name in its menu bar.

## Menu items as data

The menu's contents are fully supported, and a menu item is a good fit
for the same hiccup-flavored data-first style as everything else here.
Each item is a map, and `:separator` stands for itself. An item carries
either an `:action`, an ObjC selector string sent to a *nil* target
that resolves through the responder chain (this is how the standard
items, About, Hide, Hide Others, Show All, Quit, work with zero code on
this app's side), or a `:handler`, a Clojure function wired through the
same `GlitterTarget`/`fire:` registry the reconciler's own widgets use,
keyed by the `NSMenuItem` pointer.

Two AppKit details shape the implementation. Key equivalents default to
the Command modifier; anything else, like Hide Others' Option-Command-H,
sets `keyEquivalentModifierMask` explicitly from the
`NSEventModifierFlag` bits. And `NSMenu` auto-enables items by
validating the responder chain, so a nil-target item whose selector
nobody answers is disabled for free. That's why the standard items just
work, and why a typo'd selector shows up grayed out instead of
crashing.

## Preferences

A second window with one preference: the main window's background
color, picked from the shared `NSColorPanel` and stored in `state`
alongside everything else, as a plain sRGB `[r g b a]` vector (`nil`
meaning system default).

Colors cross the FFI boundary in both directions. Reading one back from
the panel needs a colorspace conversion first, since a panel's color
can live in any colorspace, catalog colors included, and only sRGB
components are useful here. Writing one is a single call:
`colorWithSRGBRed:green:blue:alpha:` takes four doubles, exactly the
shape glitter-uikit's `objc-msg-send-4d` already binds.

The panel is one more citizen of the `fire:` registry, target
`w/invoker`, handler keyed by the panel pointer. `setContinuous:` makes
it fire while the user drags, and every change routes through the
normal action pipeline as `[[:action/set-bg [r g b a]]]`; the panel
never touches `state` directly.

Placement taught a real lesson. The Preferences window originally
centered itself, landing as a near-perfect eclipse of the equally
centered main window, one stray click from being lost entirely. The fix
generalizes to every window this app opens: place it near the pointer
instead, at `setFrameTopLeftPoint:`, since that's where the user's
attention already is. AppKit constrains a frame that would land
offscreen when the window is shown, so a click near a screen edge is
still safe.

## Opening an example

Each example opens in its own window, created lazily and cached by id.
`setReleasedWhenClosed:NO` keeps the cached pointer valid after a
close, and `mount-second!` runs with a per-example watch key so every
open example window holds its own render watch on its own state atom
(see [Architecture](architecture.md) for why a shared key would be a
bug).

The registry's optional `:on-open` hook runs on every open *and*
reopen, and it receives the window's pointer. Live Currency uses it to
refetch rates each time its window comes forward; the Particle Toy uses
it to start (or confirm) its animation timer, which is also why the
hook carries a window argument at all: the timer needs to know which
window to watch for closing.

An earlier version tried reading the hub window's frame to cascade new
windows off it, and hit a real FFI wall: the binding layer can return
an `NSPoint` (through an out-buffer), but has no `NSRect` reader, so a
window's frame is unreadable from Clojure. That constraint is what
pushed the design toward opening at the pointer instead, which turned
out to be the better placement anyway.
