# VRKA 5.0 — Navigation Reference and Implemented Design

## SpotiFLAC-Mobile source inspected

Reference: [SpotiFLAC-Mobile](https://github.com/spotiflacapp/SpotiFLAC-Mobile), especially `lib/screens/main_shell.dart` and its `MornyeBottomBar` integration.

The inspected Flutter shell places a dedicated floating bottom bar in a safe-area layout, drives the selected destination alongside its `PageView`, and passes a blur setting to the bar. The shell disables backdrop blur on low-end devices because repeated filtering during scrolling is expensive. These are the useful implementation ideas for VRKA: keep the bar as its own surface, synchronize selection with page movement, and treat backdrop blur as a capability with a fallback. VRKA does not use Flutter code or dependencies.

## Current VRKA implementation

`VrkaFloatingNavBar` stays in Jetpack Compose and uses VRKA's existing backdrop and theme systems. When the platform renderer supports the effect, the outer capsule samples the app backdrop with a blur and a directional rim highlight, with no lens or refraction; otherwise it falls back to a translucent theme-colored fill. The selected indicator never samples the backdrop: it is drawn with plain canvas calls under the tabs.

The indicator width is computed from the measured capsule width and destination count. The bar is 64 dp high, the capsule has 32 dp corners, and the indicator uses equal 4 dp insets and is vertically centered at 56 dp high. Pager offsets carry the indicator 1:1 with the live page position. Taps and drags on the bar lift it past the capsule on springs, squash it with its acceleration and set it down on landing (`VrkaNavPillMotion.kt`), with haptic feedback at slot crossings.

The destination icons render at 24 dp; their existing vector strokes have been increased for better visual weight. Labels continue to use the current VRKA type styles. The container retains theme-specific translucent fills, subtle edge strokes, and a neutral soft shadow rather than a saturated glow.

## Limits

The current implementation gates the blur path on platform rendering support, but does not benchmark device performance or dynamically classify low-end hardware. Physical verification of blur quality, clipping, optical centering, touch targets, and legibility at narrow widths remains pending on the maintainer's OnePlus 11 and other devices.
