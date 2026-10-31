# ColorOS 17 progress wake — QA previews

## Provenance

- Reference: `F:\VID_20261030_142946.mp4` (H.264, 1280x708, 60 fps, 2.3 s). In video frame 18
  the track spans x=62..1220 and the fill head is at x=707, a measured fill of 55.7 %. Frames
  006, 018 and 023 are included as source frames.
- Captures: `ProgressPreviewCaptureTest` on the Android 16 (API 36) emulator, 1080 px wide with
  a 14 px track and a 5x-track canvas. The harness calls the production `GlowProgressRenderer`,
  the same geometry and shaders used by `VrkaGlowProgressBar`.
- `comparison_reference_vs_app.png`: both sides show a 51 % fill. The recording has no 51 %
  frame, so frame 18 was cropped at x=173 (left edge only) to make its measured fill exactly
  51 %; the head, track and luminous wake are untouched by the crop except that the reference
  bar's left rounded cap is outside the crop. This is stated on the sheet.
- `comparison_reference_vs_app_557.png`: the uncropped reference bar and the app rendered at the
  same measured 55.7 %, included to show the full bar without cropping.
- `animated.gif`: 60 rendered frames at 30 fps over a two-second 0→100 % ease-out sweep. It is a
  render of the production drawing code, not a screen recording.
- The app fill is the required `#8140DC` purple over a charcoal track; the reference player's
  fill is white. The comparisons are compositional (wake length, taper, dispersion, edge
  behaviour), not pixel-parity claims.

## Rendering under test

- **Streak:** a long horizontal white alpha ramp inside the fill, 18 track heights long,
  rounded at the head and fully transparent at its trailing end.
- **Optical bloom:** a cached 512x128 analytic teardrop alpha mask, drawn additively at two
  scales. Its silhouette and vertical profile both reach zero at the mask edge, so scaling it
  cannot expose a contour or a rectangular boundary. No per-frame bitmap, blur, `saveLayer` or
  clip is used.
- **Layout:** the component reserves five track heights for the bloom; only the small forward
  part of the teardrop spills past the real progress edge.
- **Static at rest:** position derives from the actual progress value; there is no independent
  pulse or fake animation, and reduced motion still snaps to the real value.

## Files

- `progress_000.png` … `progress_100.png`: 0, 1, 5, 12, 20, 27, 35, 43, 51, 58, 66, 80 and
  100 %.
- `progress_base.png`: purple fill only at 51 % (`showStreak=false`, `showDispersion=false`).
- `progress_streak_only.png`: purple fill + streak, bloom disabled.
- `progress_dispersion_only.png`: purple fill + teardrop bloom, streak disabled.
- `progress_combined.png`: all layers at 51 %.
- `progress_low.png` (12 %), `progress_medium.png` (51 %), `progress_high.png` (80 %).
- `boundary_100.png`: full bar at the 100 % boundary.
- `before_progress_051.png`, `before_comparison_reference_vs_app.png`: the previous renderer
  from commit `ff41bef`, kept for before/after review.
- `comparison_reference_vs_app.png` (51 %, cropped reference) and
  `comparison_reference_vs_app_557.png` (55.7 %, full reference bar).
- `animated.gif`: animation preview.
- `reference_frame_006.png`, `reference_frame_018.png`, `reference_frame_023.png`: source video
  frames.

## Automated pixel contracts

`ProgressPreviewCaptureTest` (7 instrumentation tests) asserts:

- the streak spans at least 12 track heights and fades towards the tail;
- the head is the brightest point and the streak layer never paints beyond the fill edge;
- the bloom lights the space above and below the bar, fades inside the canvas, and does not
  light above the bar when disabled;
- the streak and bloom are independently visible with the other layer disabled;
- the purple fill remains visible behind the wake;
- zero progress leaves only the track;
- no pixel ahead of the head exceeds the head's brightness.

`GlowProgressCaptureTest` (2 tests) renders the real composable at the strip (3 dp) and queue
(4 dp) heights for 1, 5, 12, 20, 27, 35, 43, 51, 58, 66, 80 and 100 %, and passes.

## Limitations

- No physical device was attached; all captures are emulator renders.
- The reference background is a video, while VRKA renders on its dark surface; brightness is
  therefore not directly comparable and no exact ColorOS parity is claimed.
- The 51 % reference sheet uses the documented left crop because the supplied recording only
  contains 54.2–55.7 % frames.
- `animated.gif` is a generated frame sequence, not a live transfer recording.
- No frame-rate or 120 Hz measurement is claimed; the only performance statement is structural
  (one cached mask, five draw calls, no per-frame bitmap or shader allocation).
