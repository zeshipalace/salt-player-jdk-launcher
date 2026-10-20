#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
# Copyright (c) 2026 Zeshi Palace
"""Verify build-time AOT timestamp-check removal using an isolated jpackage app."""
import argparse
from contextlib import nullcontext
import os
from pathlib import Path
import subprocess
import tempfile


def run(command, **kwargs):
    result = subprocess.run([str(value) for value in command], capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stdout!r}\n{result.stderr!r}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, help="New directory to retain isolated diagnostics")
    args = parser.parse_args()
    jdk = args.jdk.resolve()
    sources = Path(__file__).resolve().parents[1]
    patcher = sources / "src/main/java/com/xuncorp/saltplayer/launcher/AotCachePatcher.java"
    windows = os.name == "nt"
    suffix = ".exe" if windows else ""
    if args.work_dir:
        args.work_dir.mkdir(parents=True, exist_ok=False)
    context = nullcontext(str(args.work_dir.resolve())) if args.work_dir else tempfile.TemporaryDirectory(prefix="salt-aot-image-")
    with context as temporary:
        root = Path(temporary)
        source = root / "AotImageProbe.java"
        source.write_text(
            'import java.nio.file.*;\n'
            'public class AotImageProbe { public static void main(String[] args) throws Exception {\n'
            'Files.writeString(Path.of(args[0]), System.getProperty("java.vm.info")); } }\n',
            encoding="utf-8"
        )
        classes = root / "classes"
        inputs = root / "inputs"
        inputs.mkdir()
        run([jdk / f"bin/javac{suffix}", "-d", classes, source])
        run([jdk / f"bin/jar{suffix}", "--create", "--file", inputs / "probe.jar", "-C", classes, "."])
        run([
            jdk / f"bin/jpackage{suffix}", "--type", "app-image", "--name", "Aot Probe",
            "--input", inputs, "--main-jar", "probe.jar", "--main-class", "AotImageProbe",
            "--dest", root / "images", "--add-modules", "java.base",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-XX:UseAVX=0", "--java-options", "-XX:AOTCache=$APPDIR/probe.aot",
            "--java-options", "-XX:AOTMode=on", "--java-options", "-XX:+VerifySharedSpaces",
            "--java-options", "-Xlog:aot=info,class+load=info:file=class-load.log"
        ])
        image = root / "images/Aot Probe"
        app = image / ("app" if windows else "lib/app")
        launcher = image / ("Aot Probe.exe" if windows else "bin/Aot Probe")
        launcher_before = launcher.read_bytes()
        jar = app / ("ffmpeg-x64.dll" if windows else "ffmpeg-x64.so")
        (app / "probe.jar").rename(jar)
        config = app / "Aot Probe.cfg"
        config.write_text(config.read_text(encoding="utf-8").replace("probe.jar", jar.name), encoding="utf-8")
        config_before = config.read_bytes()
        cache = app / "probe.aot"
        java = image / ("runtime/bin/java.exe" if windows else "lib/runtime/bin/java")
        output = root / "result.txt"
        run([java, "-XX:UseAVX=0", f"-XX:AOTCacheOutput={cache}", "-cp", jar, "AotImageProbe", output], cwd=root)
        assert cache.is_file() and cache.stat().st_size > 0, "AOT cache not created"
        original = jar.read_bytes()
        stamp = jar.stat().st_mtime_ns

        def launch():
            output.unlink(missing_ok=True)
            result = run([launcher, output], cwd=root)
            text = output.read_text(encoding="utf-8")
            class_log = (root / "class-load.log").read_text(encoding="utf-8")
            assert text == "mixed mode, sharing", (text, result.stdout, result.stderr)
            assert "AotImageProbe source: shared objects file" in class_log, class_log

        launch()
        print("PASS: original jpackage launcher loads application classes from AOT")
        changed = stamp + 120000000000
        os.utime(jar, ns=(changed, changed))
        baseline = subprocess.run([
            str(java), "-XX:UseAVX=0", "-XX:AOTMode=on", f"-XX:AOTCache={cache}",
            "-Xlog:aot=info", "-cp", str(jar), "AotImageProbe", str(output)
        ], cwd=root, capture_output=True)
        assert baseline.returncode != 0 and b"timestamp has changed" in baseline.stdout + baseline.stderr
        os.utime(jar, ns=(stamp, stamp))

        run([jdk / f"bin/java{suffix}", patcher, cache, jar])
        patched = cache.read_bytes()
        print("PASS: build-time patch succeeds; unpatched control rejects changed timestamps")
        os.utime(jar, ns=(changed, changed))
        if windows:
            run(["icacls", jar, "/deny", "*S-1-1-0:(WA)"])
        try:
            for attempt in range(3):
                launch()
                assert jar.stat().st_mtime_ns == changed
                assert jar.read_bytes() == original
                assert cache.read_bytes() == patched
            print("PASS: 3 launches load AOT with modified JAR time, required AOT, and full archive CRC validation")
            if windows:
                print("PASS: AOT works even when file timestamp writes are denied")
        finally:
            if windows:
                run(["icacls", jar, "/remove:d", "*S-1-1-0"])

        run([jdk / f"bin/java{suffix}", patcher, cache, jar])
        assert cache.read_bytes() == patched
        assert launcher.read_bytes() == launcher_before
        assert config.read_bytes() == config_before
        assert not (app / "probe.aot.manifest").exists()
        print("PASS: patch is idempotent; original launcher/cfg, JAR content/time, and cache remain untouched at startup")


if __name__ == "__main__":
    main()
