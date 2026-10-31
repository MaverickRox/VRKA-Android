# ColorOS 17 progress wake — QA previews

Provenance:

- Reference: `F:\VID_20261030_142946.mp4` (H.264, 1280x708, 60 fps, 2.345 s). Frames 006, 018 and 023 are included for direct comparison.
- Captures: `ProgressPreviewCaptureTest` on the Android 16 (API 36) emulator, 1080x2400, density 420. The harness calls the production `GlowProgressRenderer`, the same geometry and shaders used by `VrkaGlowProgressBar`, on a 1080x72 px canvas with a 24 px track. Images are PNGs pulled from the app's external files directory.
- `animated.gif`: 60 rendered frames at 30 fps over a two-second 0→100 % ease-out sweep. It is a render of the production drawing code, not a screen recording.
- `comparison_reference_vs_app.png`: reference video frame above the rendered implementation at 51 %.

The app fill is intentionally the required `#8140DC` purple over a charcoal track; the reference video's player is white-filled. The comparison is compositional (length, taper, dispersion, edge behaviour), not a pixel-parity claim.

Pixel-level contracts asserted by the capture test:

- No luminous pixel exists ahead of the real progress edge (rendered output is identical to a wake-disabled render for every pixel past the edge).
- The wake is brighter than the track at the edge and disperses above and below the purple fill.
- The fill stays purple behind the wake, and zero progress draws only the track.

Files:

- `progress_000.png` … `progress_100.png`: 0, 1, 5, 12, 20, 27, 35, 43, 51, 58, 66, 80 and 100 %.
- `base_purple.png`: fill only, no wake (51 %).
- `streak_without_haze.png`: streak and teardrop layers without the outer haze (51 %).
- `complete_wake.png`: all layers (51 %).
- `low_012.png`, `medium_051.png`, `high_080.png`: low, medium and high progress.
- `boundary_100.png`: full bar at the 100 % boundary.
- `progress_range.png`: all thirteen percentages stacked for review.
- `animated.gif`: animation preview.
- `reference_frame_006.png`, `reference_frame_018.png`, `reference_frame_023.png`: source video frames.
