# VRKA 5.0 — Navigation Reference and Implemented Design

## SpotiFLAC-Mobile source inspected

Reference: [SpotiFLAC-Mobile](https://github.com/spotiflacapp/SpotiFLAC-Mobile), especially `lib/screens/main_shell.dart` and its `MornyeBottomBar` integration.

The inspected Flutter shell places a dedicated floating bottom bar in a safe-area layout, drives the selected destination alongside its `PageView`, and passes a blur setting to the bar. The shell disables backdrop blur on low-end devices because repeated filtering during scrolling is expensive. These are the useful implementation ideas for VRKA: keep the bar as its own surface, synchronize selection with page movement, and treat backdrop blur as a capability with a fallback. VRKA does not use Flutter code or dependencies.

## Current VRKA implementation

`VrkaFloatingNavBar` stays in Jetpack Compose and uses VRKA's existing backdrop and theme systems. It renders the outer capsule and selected indicator as separate translucent layers. When the platform renderer supports the effect, each samples the app backdrop with its own blur and highlight; the active indicator adds a restrained lens/refraction effect. Otherwise, both fall back to translucent theme-colored fills.

The indicator width is computed from the measured capsule width and destination count. Its horizontal position is the animated destination coordinate multiplied by the measured slot width, plus the shared inset. The bar is 64 dp high, the capsule has 32 dp corners, and the indicator uses equal 4 dp insets and is vertically centered at 56 dp high. Pager offsets snap the indicator to the live page position; taps animate between destination centers, and drags track the pointer after touch slop with haptic feedback at slot crossings.

The destination icons render at 24 dp; their existing vector strokes have been increased for better visual weight. Labels continue to use the current VRKA type styles. The container retains theme-specific translucent fills, subtle edge strokes, and a neutral soft shadow rather than a saturated glow.

## Limits

The current implementation gates the blur/lens path on platform rendering support, but does not benchmark device performance or dynamically classify low-end hardware. Physical verification of blur quality, clipping, optical centering, touch targets, and legibility at narrow widths remains pending on the maintainer's OnePlus 11 and other devices.
