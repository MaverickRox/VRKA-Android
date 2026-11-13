# Third-Party Notices & Licenses

VRKA Android (distributed under GNU General Public License v3.0, see `LICENSE`) incorporates open-source libraries, native binaries, browser extensions, fonts, and vendored components listed below alongside their observed version and licensing terms (verified against Gradle resolved runtime dependencies, Maven POM metadata, bundled native binary strings, and source headers).

---

### 1. Mozilla GeckoView
- **Artifact**: `org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261022150525`
- **License**: Mozilla Public License 2.0 (MPL-2.0)
- **Source**: https://hg.mozilla.org/mozilla-central/

### 2. uBlock Origin
- **Component**: uBlock Origin (`uBlock0@raymondhill.net` v1.74.0, bundled at `app/src/main/assets/extensions/ublock.xpi`)
- **Author**: Raymond Hill (gorhill)
- **License**: GNU General Public License v3.0 (GPL-3.0); bundled web assets inside the XPI carry their respective upstream permissive licenses (e.g., Inter OFL-1.1, Metropolis Unlicense, CodeMirror MIT)
- **Source**: https://github.com/gorhill/uBlock

### 3. yt-dlp (Python zipapp)
- **Component**: Stock `yt-dlp` (`2026.08.19`, bundled at `app/src/main/res/raw/ytdlp`)
- **License**: The Unlicense
- **Source**: https://github.com/yt-dlp/yt-dlp

### 4. youtubedl-android Wrapper (`library` & `common`)
- **Artifacts**: `io.github.junkfood02.youtubedl-android:library:0.18.1`, `io.github.junkfood02.youtubedl-android:common:0.18.1`
- **License**: GNU General Public License v3.0 (GPL-3.0) per upstream Maven POM (`GPL-3.0 license`) and repository license
- **Bundled Native Payloads (`library-0.18.1.aar`)**: CPython (`libpython.so`, `libpython.zip.so`, PSF-2.0) and QuickJS (`libqjs.so`, MIT)
- **Source**: https://github.com/yausername/youtubedl-android

### 5. FFmpeg (`youtubedl-android:ffmpeg`)
- **Maven Artifact**: `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` (`ffmpeg-0.18.1.aar`, 139,371,444 bytes, SHA-256: `0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49`)
- **Bundled Native Binaries (`app-release.apk` under `lib/arm64-v8a/`)**:
  - `libffmpeg.so`: 317,120 bytes, SHA-256: `1b8d5c371ea79e6e7a1930b31f2ad6a2731e48d75ceddc9c36dffda6e0092401` (ELF PIE executable front-end for `ffmpeg`, linking against `libav*.so`)
  - `libffprobe.so`: 233,664 bytes, SHA-256: `a4d4a6d1562aa87934e99ee1a43f8090ec1040659cedb667d6edf3aa08408c5e` (ELF PIE executable front-end for `ffprobe`, linking against `libav*.so`)
  - `libffmpeg.zip.so`: 35,624,931 bytes, SHA-256: `4664a895283f9f31786be39b0c484c1f96631f02c7c7f9c70a4a4a2da324a092` (ZIP archive holding Termux root filesystem `usr/lib/` containing 178 binary objects extracted at runtime by `com.yausername.ffmpeg.FFmpeg.init(Context)`)
- **FFmpeg Version & Build Configuration**:
  - **Version**: **FFmpeg 7.1.1** (confirmed via binary string in `usr/lib/libavutil.so.59.39.100`: `version 7.1.1`, upstream release date March 3, 2025).
  - **Toolchain**: Android NDK r28c (`pkg-config=/home/builder/.termux-build/_cache/android-r28c-api-24-v1/bin/pkg-config`), Android API 24 target (`aarch64-linux-android-clang`), LLVM toolchain.
  - **Configure Flags (embedded in `libavutil.so.59.39.100`)**:
    `--arch=aarch64 --as=aarch64-linux-android-clang --cc=aarch64-linux-android-clang --cxx=aarch64-linux-android-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --pkg-config=/home/builder/.termux-build/_cache/android-r28c-api-24-v1/bin/pkg-config --strip=llvm-strip --cross-prefix=aarch64-linux-android- --disable-indevs --disable-outdevs --enable-indev=lavfi --disable-static --disable-symver --enable-cross-compile --enable-gnutls --enable-gpl --enable-version3 --enable-jni --enable-lcms2 --enable-libaom --enable-libass --enable-libbluray --enable-libdav1d --enable-libfontconfig --enable-libfreetype --enable-libfribidi --enable-libgme --enable-libharfbuzz --enable-libmp3lame --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenmpt --enable-libopus --enable-librav1e --enable-librubberband --enable-libsoxr --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtheora --enable-libv4l2 --enable-libvidstab --enable-libvmaf --enable-libvo-amrwbenc --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-libzimg --enable-libzmq --enable-mediacodec --enable-opencl --enable-shared --prefix=/data/data/com.termux/files/usr --target-os=android --extra-libs=-landroid-glob --disable-vulkan --enable-neon --disable-libfdk-aac`
- **License Classification**:
  - The effective license of the bundled FFmpeg native binaries and shared libraries is **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)**. Binary metadata confirms `license: GPL version 3 or later`.
  - The binary is built with `--enable-gpl --enable-version3` and links GPL-licensed external libraries (`libpostproc.so.58.3.100`, `libx264.so.164`, `libx265.so`, `libxvidcore.so.4.3`, `libvidstab.so`, `librubberband.so`, `libfftw3.so`), as well as Apache-2.0 licensed libraries (`libopencore-amrnb.so`, `libopencore-amrwb.so`, `libvo-amrwbenc.so.0.0.4`) which require GPLv3 compatibility.
- **Component Separation**:
  - **Android Wrapper**: `com.yausername.ffmpeg.FFmpeg` inside `classes.jar` (`ffmpeg-0.18.1.aar`), licensed under GNU General Public License v3.0 (`GPL-3.0`).
  - **Core FFmpeg Libraries (`usr/lib/` inside `libffmpeg.zip.so`)**: `libavcodec.so.61.19.101`, `libavformat.so.61.7.100`, `libavfilter.so.10.4.100`, `libavdevice.so.61.3.100`, `libswscale.so.8.3.100`, `libswresample.so.5.3.100`, `libpostproc.so.58.3.100`, `libavutil.so.59.39.100` (`GPL-3.0-or-later`).
  - **Separately Bundled Third-Party Codec & Support Libraries**:
    - *GPLv2+*: VideoLAN x264 (`libx264.so.164`), MulticoreWare x265 (`libx265.so`), Xvid (`libxvidcore.so.4.3`), vid.stab (`libvidstab.so`), Breakfast Quay Rubber Band (`librubberband.so`, `librubberband-jni.so`), FFTW (`libfftw3.so`, `libfftw3f.so`, `libfftw3l.so`).
    - *LGPL-2.0+ / LGPL-2.1+ / LGPL-3.0+*: LAME MP3 (`libmp3lame.so`), libbluray (`libbluray.so`), SoX Resampler (`libsoxr.so`), libssh (`libssh.so`), Game Music Emu (`libgme.so`), GnuTLS (`libgnutls.so`, `libgnutls-dane.so`), ZeroMQ (`libzmq.so`), Video4Linux (`libv4l1.so`, `libv4l2.so`, `libv4lconvert.so`), GNU FriBidi (`libfribidi.so`), GNU libiconv (`libiconv.so`), GNU unistring (`libunistring.so`), Nettle (`libnettle.so`, `libhogweed.so`), GMP (`libgmp.so`), GNOME GLib/GIO/GObject (`libglib-2.0.so`, `libgio-2.0.so`, `libgobject-2.0.so`), GNU libidn2 (`libidn2.so`).
    - *Permissive / BSD / MIT / Apache-2.0 / Other*: dav1d (`libdav1d.so`, BSD-2-Clause), rav1e (`librav1e.so.0.8.1`, BSD-2-Clause), SVT-AV1 (`libSvtAv1Enc.so`, BSD-3-Clause), libaom (`libaom.so`, BSD-2-Clause), libvpx (`libvpx.so.11.0.1`, BSD-3-Clause), Opus (`libopus.so`, BSD-3-Clause), Vorbis & Ogg (`libvorbis.so`, `libogg.so`, BSD-3-Clause), Theora (`libtheora.so`, BSD-3-Clause), WebP (`libwebp.so`, BSD-3-Clause), libass (`libass.so`, ISC), FreeType (`libfreetype.so`, FTL/GPLv2), HarfBuzz (`libharfbuzz.so`, MIT), Fontconfig (`libfontconfig.so`, HPND/MIT-like), libxml2 (`libxml2.so.16.0.6`, MIT), Netflix VMAF (`libvmaf.so`, BSD-2-Clause-Patent), SRT (`libsrt.so`, MPL-2.0), OpenMPT (`libopenmpt.so`, BSD-3-Clause), zimg (`libzimg.so`, WTFPL/Zlib), Little CMS (`liblcms2.so`, MIT), Secret Rabbit Code (`libsamplerate.so`, BSD-2-Clause), OpenCORE AMR (`libopencore-amrnb.so`, `libopencore-amrwb.so`, `libvo-amrwbenc.so.0.0.4`, Apache-2.0), Google Brotli (`libbrotlicommon.so`, `libbrotlidec.so`, `libbrotlienc.so`, MIT), PCRE2 (`libpcre2-8.so`, `libpcre2-16.so`, `libpcre2-32.so`, BSD-3-Clause), libpng (`libpng16.so`, libpng License), zlib (`libz.so.1.3.1`, zlib License), bzip2 (`libbz2.so.1.0.8`, BSD-like), XZ liblzma (`liblzma.so.5.8.1`, Public Domain), libsodium (`libsodium.so`, ISC), Graphite2 (`libgraphite2.so`, LGPL-2.1+/MPL-2.0), libffi (`libffi.so`, MIT).
- **Corresponding Source Code, Recipes & Provenance**:
  - **FFmpeg 7.1.1 Upstream Source**: Official release tarball available at https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz and Git repository at https://git.ffmpeg.org/ffmpeg.git (tag `n7.1.1`, commit `93e0b731d77cbcf8b1a82f3ef8421d604e38e55c`).
  - **Packaging & Build Recipes**: The cross-compilation harness, build scripts, configure flags, patches, and Docker environment used to produce these exact binaries are maintained by `termux-packages` at https://github.com/termux/termux-packages (package recipe `packages/ffmpeg/build.sh`) and documented by `youtubedl-android` at https://github.com/yausername/youtubedl-android (`BUILD_FFMPEG.md`) and fork https://github.com/junkfood02/youtubedl-android (release tag `v0.18.1`).
- **Written Offer for Corresponding Source (GNU GPL-3.0 Section 6)**:
  - In accordance with Section 6(b) and Section 6(d) of the GNU General Public License v3.0, the VRKA Android maintainers offer to provide to any recipient of the VRKA Android v5.1.5 binary distribution (`app-release.apk`) the complete machine-readable Corresponding Source code for all software covered by the GNU General Public License v3.0 (including the bundled FFmpeg 7.1.1, youtubedl-android wrapper, and all associated GPL/LGPL third-party libraries, patchsets, configure scripts, and build harness instructions).
  - **Offer Validity**: This offer is valid for at least **three (3) years** from the release date of VRKA Android v5.1.5 (November 13, 2026), and for as long as customer support or release distributions are offered for this version.
  - **Online Access**: Machine-readable Corresponding Source code and build scripts are publicly available free of charge at the designated network servers:
    - VRKA Android Repository: https://github.com/MaverickRox/VRKA-Android
    - youtubedl-android Repository: https://github.com/yausername/youtubedl-android and https://github.com/junkfood02/youtubedl-android
    - Termux Packages Recipe Repository: https://github.com/termux/termux-packages
    - Official FFmpeg Source Tarballs: https://ffmpeg.org/releases/
  - **Physical Media Option**: Any recipient may also request a copy of the Corresponding Source on physical media (such as a USB flash drive or DVD) by contacting the project maintainer via the GitHub issue tracker at https://github.com/MaverickRox/VRKA-Android/issues or by emailing `contact@mvrk.dev`. A nominal charge no greater than the reasonable cost of physically conveying the source medium may be assessed. All software components conveyed under this license are governed by GNU General Public License v3.0 (see `LICENSE`).

### 6. Bouncy Castle Cryptography APIs
- **Artifacts**: `org.bouncycastle:bcprov-jdk18on:1.85.2`, `org.bouncycastle:bcpg-jdk18on:1.85`, `org.bouncycastle:bcutil-jdk18on:1.85`
- **Author**: The Legion of the Bouncy Castle Inc. (Copyright © 2000–2026)
- **License**: Bouncy Castle Licence (MIT License; included verbatim in JAR `META-INF/LICENSE.md`); `bcpg-jdk18on` also carries Apache-2.0 notice in Maven POM
- **Source**: https://www.bouncycastle.org/

### 7. Kyant0 Backdrop (`ui/backdrop`, vendored source)
- **Component**: Vendored `Kyant0/backdrop` v2.0.0 (`io.github.kyant0:backdrop`), located under `app/src/main/java/com/mvrk/vrka/ui/backdrop/`
- **Copyright**: Copyright 2025 Kyant0
- **License**: Apache License 2.0 (Apache-2.0)
- **Source**: https://github.com/Kyant0/backdrop

### 8. AndroidX & Jetpack Libraries
- **Direct Artifacts**:
  - `androidx.room:room-runtime:2.8.5`, `room-ktx:2.8.5`, `room-paging:2.8.5` (and `androidx.sqlite:*:2.6.2`)
  - `androidx.paging:paging-runtime:3.5.3`, `paging-compose:3.5.3`
  - `androidx.work:work-runtime-ktx:2.10.0` (and `work-runtime:2.10.0`)
  - `androidx.media3:media3-{common,exoplayer,exoplayer-hls,exoplayer-dash,datasource,extractor,decoder,container,database}:1.11.1`
  - `androidx.datastore:datastore-preferences:1.2.1` (and `datastore-core-android:1.2.1`, `datastore-preferences-core:1.2.1`, `datastore-preferences-proto:1.2.1`)
  - `androidx.compose:compose-bom:2026.06.00` (`ui`, `ui-tooling-preview`, `foundation`, `animation`, `runtime` `1.11.3`; `material3:1.4.0`)
  - `androidx.core:core-ktx:1.19.1`, `androidx.activity:activity-compose:1.13.0`, `androidx.lifecycle:*:2.11.0`, `androidx.appcompat:appcompat:1.4.2`, `androidx.documentfile:documentfile:1.0.0`, `androidx.exifinterface:exifinterface:1.3.6`, `androidx.graphics:graphics-path:1.0.1`, `androidx.profileinstaller:profileinstaller:1.4.0`
- **License**: Apache License 2.0 (Apache-2.0), except transitive `androidx.datastore:datastore-preferences-external-protobuf:1.2.1` which is **BSD-3-Clause** per Maven POM metadata
- **Source**: https://developer.android.com/jetpack/androidx

### 9. OkHttp & Okio
- **Artifacts**: `com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okio:okio:3.9.1`, `com.squareup.okio:okio-jvm:3.9.1`
- **License**: Apache License 2.0 (Apache-2.0)
- **Source**: https://square.github.io/okhttp/

### 10. Jackson JSON Processor (transitive via `youtubedl-android`)
- **Artifacts**: `com.fasterxml.jackson.core:jackson-core:2.11.1`, `jackson-databind:2.11.1`, `jackson-annotations:2.11.1`
- **License**: Apache License 2.0 (Apache-2.0)
- **Source**: https://github.com/FasterXML/jackson

### 11. Apache Commons & Guava (transitive dependencies)
- **Artifacts**: `org.apache.commons:commons-compress:1.12`, `commons-io:commons-io:2.5`, `com.google.guava:guava:33.3.1-android`, `com.google.guava:failureaccess:1.0.2`, `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava`
- **License**: Apache License 2.0 (Apache-2.0; inherited from parent POMs `org.apache:apache:13` and `com.google.guava:guava-parent:26.0-android`)

### 12. Kotlin & JetBrains Libraries
- **Artifacts**: `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` (`kotlin-stdlib-jdk7:1.9.0`, `kotlin-stdlib-jdk8:1.8.21`), `org.jetbrains.kotlinx:kotlinx-coroutines-{android,core,core-jvm}:1.10.2`, `org.jetbrains.kotlinx:kotlinx-serialization-{core,core-jvm,json,json-jvm}:1.7.3`, `org.jetbrains:annotations:23.0.0`, `org.jspecify:jspecify:1.0.0`
- **License**: Apache License 2.0 (Apache-2.0)

### 13. SnakeYAML & Google Play Services (transitive via GeckoView)
- **SnakeYAML**: `org.yaml:snakeyaml:2.2` — Apache License 2.0 (Apache-2.0)
- **Google Play Services SDK bindings**: `com.google.android.gms:play-services-{base:18.7.2, basement:18.9.0, fido:21.3.1, tasks:18.3.2}` — **Android Software Development Kit License** per Maven POM metadata; pulled transitively by `org.mozilla.geckoview` (no proprietary Play Services code is authored by VRKA)

### 14. Space Mono Font
- **Component**: Space Mono (`app/src/main/assets/licenses/space_mono_ofl.txt`)
- **Author**: Colophon Foundry
- **License**: SIL Open Font License 1.1 (OFL-1.1)
- **Source**: https://github.com/googlefonts/spacemono

### 15. Mornye Switch Component
- **Project**: Mornye 5.2.6 (`AppSwitch` / `_MornyeSwitch`, `lib/widgets/app_switch.dart`)
- **License**: GNU Lesser General Public License v3.0 (LGPL-3.0); incorporated into VRKA under GPL-3.0 as permitted by LGPL-3.0 section 3
- **Source**: https://github.com/MaverickRox/Mornye
- **Use**: geometry, timing, drag and accessibility behavior of `VrkaSwitch.kt`

### 16. liquid_glass_easy Navigation Motion
- **Project**: liquid_glass_easy 4.3.4 (`LiquidGlassAnimatedNavBar`, `LiquidGlassLensMotion`)
- **Author**: Ahmed Gamil (Copyright © 2025)
- **License**: MIT License
- **Source**: https://pub.dev/packages/liquid_glass_easy
- **Use**: spring, lift and squash motion parameters of the selection pill in `VrkaNavPillMotion.kt`; no rendering code is used

### 17. Test Dependencies (Unit & Instrumented Tests Only; Not Distributed in Release APK)
- JUnit 4 (`junit:junit:4.13.2`, EPL-1.0), `org.jetbrains.kotlin:kotlin-test-junit` (Apache-2.0), `org.json:json` (JSON License, host unit-test stubbing only), AndroidX Test (`runner`, `ext:junit`, `espresso-core`, `ui-test-junit4`, `work-testing`, `room-testing`), Apache-2.0.

