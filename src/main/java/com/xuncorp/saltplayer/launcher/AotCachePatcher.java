/*
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 * Copyright (c) 2026 Zeshi Palace
 */
package com.xuncorp.saltplayer.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Disables application JAR timestamp checks in a freshly generated JDK 25 AOT cache.
 * Runs during packaging, never during application startup.
 */
public final class AotCachePatcher {
    // Layout from JetBrainsRuntime 95357d90d8bde24fd0d3788ae33c8871b0b5b8db
    // src/hotspot/share/include/cds.h and src/hotspot/share/cds/aotClassLocation.hpp
    private static final int ARCHIVE_MAGIC = 0xf00baba2;
    private static final int ARCHIVE_VERSION = 19;
    private static final int GENERIC_HEADER_SIZE = 24;
    private static final int REGION_COUNT = 5;
    private static final int REGION_SIZE = 96;
    private static final int RO_REGION_HEADER = GENERIC_HEADER_SIZE + REGION_SIZE;
    private static final int LOCATION_HEADER_SIZE = 48;
    private static final int CHECK_TIME_OFFSET = 16;
    private static final long WINDOWS_EPOCH_TICKS = 116_444_736_000_000_000L;

    private AotCachePatcher() {}

    public static int disableTimestampChecks(
        Path archive,
        List<Path> classpath
    ) throws IOException {
        require(!classpath.isEmpty(), "Application classpath is empty");
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(bytes.length >= GENERIC_HEADER_SIZE + REGION_COUNT * REGION_SIZE,
            "AOT archive header is truncated");
        require(data.getInt(0) == ARCHIVE_MAGIC && data.getInt(8) == ARCHIVE_VERSION,
            "Unsupported AOT archive format; expected JDK 25 static CDS version 19");
        int headerSize = data.getInt(12);
        require(headerSize >= GENERIC_HEADER_SIZE + REGION_COUNT * REGION_SIZE && headerSize <= bytes.length,
            "Invalid AOT archive header size");
        require(data.getInt(16) == 0 && data.getInt(20) == 0,
            "Only standalone static AOT archives are supported");
        require(crc(bytes, 16, headerSize - 16) == data.getInt(4), "AOT header CRC mismatch");

        require(data.getInt(RO_REGION_HEADER + 4) == 1
                && data.getInt(RO_REGION_HEADER + 12) == 0
                && data.getInt(RO_REGION_HEADER + 16) == 0,
            "Unexpected AOT read-only region layout");
        long roOffset = data.getLong(RO_REGION_HEADER + 24);
        long roUsed = data.getLong(RO_REGION_HEADER + 40);
        require(roOffset >= headerSize && roUsed > 0
                && roOffset <= bytes.length && roUsed <= bytes.length - roOffset,
            "Invalid AOT read-only region bounds");
        int roStart = (int) roOffset;
        int roSize = (int) roUsed;
        int roEnd = roStart + roSize;
        require(crc(bytes, roStart, roSize) == data.getInt(RO_REGION_HEADER), "AOT read-only region CRC mismatch");

        List<Integer> records = new ArrayList<>();
        Set<Integer> uniqueRecords = new HashSet<>();
        for (Path entry : classpath) {
            Path path = entry.toAbsolutePath();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            require(attributes.isRegularFile(), "Classpath entry is not a regular file: " + path);
            byte[] needle = (path + "\0").getBytes(StandardCharsets.UTF_8);
            long timestamp = vmTimestamp(attributes.lastModifiedTime().toInstant());
            List<Integer> matches = new ArrayList<>();
            for (int cursor = roStart + LOCATION_HEADER_SIZE; cursor <= roEnd - needle.length; cursor++) {
                if (bytes[cursor] != needle[0] || !matches(bytes, cursor, needle)) continue;
                int record = cursor - LOCATION_HEADER_SIZE;
                if (isLocation(data, record, needle.length - 1, roEnd, attributes.size(), timestamp)) {
                    matches.add(record);
                }
            }
            require(matches.size() == 1,
                "Expected exactly one AOT application classpath record for " + path + "; found " + matches.size());
            int record = matches.get(0);
            require(uniqueRecords.add(record), "Duplicate application classpath entry: " + path);
            records.add(record);
        }

        // Resolve every record and validate the format before changing any file bytes
        int changed = 0;
        for (int record : records) {
            if (bytes[record + CHECK_TIME_OFFSET] != 0) {
                bytes[record + CHECK_TIME_OFFSET] = 0;
                changed++;
            }
        }
        if (changed != 0) {
            data.putInt(RO_REGION_HEADER, crc(bytes, roStart, roSize));
            data.putInt(4, crc(bytes, 16, headerSize - 16));
            Files.write(archive, bytes);
        }
        return changed;
    }

    private static boolean isLocation(
        ByteBuffer data,
        int record,
        int pathLength,
        int roEnd,
        long fileSize,
        long timestamp
    ) {
        if ((record & 7) != 0 || data.getLong(record) != pathLength) return false;
        long manifestLength = data.getLong(record + 8);
        long manifestStart = (long) record + LOCATION_HEADER_SIZE + pathLength + 1;
        int checkTime = Byte.toUnsignedInt(data.get(record + CHECK_TIME_OFFSET));
        return manifestLength >= 0 && manifestLength < roEnd - manifestStart
            && data.get((int) (manifestStart + manifestLength)) == 0
            && checkTime <= 1
            && Byte.toUnsignedInt(data.get(record + 17)) <= 1
            && Byte.toUnsignedInt(data.get(record + 18)) <= 1
            && data.getInt(record + 20) == 0 // AOTClassLocation::FileType::NORMAL
            && data.getInt(record + 24) == 2 // AOTClassLocation::Group::APP_CLASSPATH
            && data.getInt(record + 28) > 0
            && (checkTime == 0 || data.getLong(record + 32) == timestamp)
            && data.getLong(record + 40) == fileSize;
    }

    private static long vmTimestamp(Instant time) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return Math.addExact(
                WINDOWS_EPOCH_TICKS,
                Math.addExact(Math.multiplyExact(time.getEpochSecond(), 10_000_000L), time.getNano() / 100L)
            );
        }
        return time.getEpochSecond();
    }

    private static boolean matches(
        byte[] bytes,
        int start,
        byte[] needle
    ) {
        for (int index = 0; index < needle.length; index++) {
            if (bytes[start + index] != needle[index]) return false;
        }
        return true;
    }

    private static int crc(
        byte[] bytes,
        int start,
        int length
    ) {
        CRC32 crc = new CRC32();
        crc.update(bytes, start, length);
        return (int) crc.getValue();
    }

    private static void require(
        boolean valid,
        String message
    ) throws IOException {
        if (!valid) throw new IOException(message);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java AotCachePatcher.java CACHE APPLICATION_JAR [APPLICATION_JAR...]");
        }
        List<Path> classpath = Arrays.stream(args).skip(1).map(Path::of).toList();
        int changed = disableTimestampChecks(Path.of(args[0]), classpath);
        System.out.println("Disabled application timestamp checks in " + args[0] + ": " + changed + " entries");
    }
}
