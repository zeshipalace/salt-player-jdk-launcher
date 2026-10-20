# Salt Player JDK launcher tooling

Build-time AOT tooling for Salt Player. `AotCachePatcher` disables modification-time
checks for application JAR entries inside a freshly generated AOT cache, then
updates the archive's read-only-region CRC and header CRC.

The generated cache is used by the original JVM and original jpackage launcher.
There is no startup helper, JAR content scan, SHA-256 calculation, metadata
manifest, file-time restoration, or custom fallback. Windows needs no replacement
launcher. Salt Player's existing Linux UTF-8 launcher is maintained separately:
[salt-player-linux-launcher](https://github.com/zeshipalace/salt-player-linux-launcher)

## Usage

Use a full JDK 25 to run the Java source file directly after AOT training, before
moving or packaging the app image. Pass the cache followed by the exact absolute
classpath files used for training:

```sh
java src/main/java/com/xuncorp/saltplayer/launcher/AotCachePatcher.java \
  /build/SaltPlayer/lib/app/voxzen.aot \
  /build/SaltPlayer/lib/app/ffmpeg-x64.so
```

```powershell
& "$env:JAVA_HOME/bin/java.exe" src/main/java/com/xuncorp/saltplayer/launcher/AotCachePatcher.java `
  'C:\build\SaltPlayer\app\voxzen.aot' `
  'C:\build\SaltPlayer\app\ffmpeg-x64.dll'
```

This needs no C/C++ compiler, Gradle, or player source code. Voxzen compiles this
same Java source into `buildSrc` and calls it from `generateReleaseAot`; it is
not included in the application runtime or installed with the application.

## Supported archive layout

This tool targets 64-bit little-endian JDK 25 static AOT/CDS archives, format
version 19. The layout is based on JetBrains Runtime `jb25.0.4.1-b635.70`, commit
`95357d90d8bde24fd0d3788ae33c8871b0b5b8db`:

- `src/hotspot/share/include/cds.h` defines the header and region layouts
- `src/hotspot/share/cds/aotClassLocation.hpp` defines the classpath records
- `src/hotspot/share/cds/aotClassLocation.cpp` checks `_check_time`
- `src/hotspot/share/cds/filemap.cpp` defines archive CRC calculation

The tool validates the format, region bounds, existing CRCs, and unique matching
application classpath records before writing. It changes only `_check_time` and
the two affected CRCs. The JARs and bundled JDK are not modified. The JVM's other
original archive-loading behavior remains unchanged. Repeated patching is a no-op.

Review the layout when changing JDK versions. An unrecognized format fails at
build time rather than producing an unchecked archive. Run the tool on the same
platform that trained the cache: Windows records full FILETIME values, while
Linux records epoch seconds.

## Validation

The integration test builds a separate minimal jpackage app inside a temporary
directory. It never starts the player or accesses player data:

```sh
python3 tests/test_image.py --jdk "$JAVA_HOME"
```

It first demonstrates rejection with an unpatched cache, then patches the cache,
changes the JAR's time, and verifies three launches with both
`-XX:AOTMode=on` and `-XX:+VerifySharedSpaces`. It verifies application classes
actually come from the archive and that no startup operation changes the JAR,
cache, original launcher, or configuration. On Windows it also denies timestamp
writes while verifying AOT remains enabled. `--work-dir NEW_DIRECTORY` retains
isolated diagnostics.

The GitHub Actions workflow runs this test on Windows x64 and Linux x64 with
JBR SDK `25.0.4.1-b635.70`.

## Source distribution

Copyright (c) 2026 Zeshi Palace. GPL-2.0-only with the Classpath exception;
see `LICENSE` and `NOTICE`.

`source-files.txt` lists the complete source package. Its source ID is the
SHA-256 of a UTF-8 checksum list sorted by relative path, with
`<lowercase file SHA-256><two spaces><relative path><LF>` per file. This source
publication checksum is computed only when preparing the source release.
