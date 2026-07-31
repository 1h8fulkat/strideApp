#!/usr/bin/env python3
"""
Unpack a Xamarin.Android assemblies.blob into individual .dll files.

The store is a flat header + fixed-size descriptors; the manifest gives us
name -> blob index, so we don't need the hash tables. Payloads are usually
LZ4-block compressed behind an "XALZ" header, decompressed here in pure
Python to avoid a native lz4 dependency.

    ./extract_assemblies.py <dir with assemblies.blob+manifest> <outdir> [name filter]
"""
import os
import struct
import sys

XABA = 0x41424158  # 'XABA' little-endian
XALZ = b"XALZ"


def lz4_decompress_block(src: bytes, expected: int) -> bytes:
    """Minimal LZ4 block-format decoder."""
    dst = bytearray()
    i = 0
    n = len(src)
    while i < n:
        token = src[i]
        i += 1

        lit_len = token >> 4
        if lit_len == 15:
            while True:
                b = src[i]
                i += 1
                lit_len += b
                if b != 255:
                    break
        dst += src[i:i + lit_len]
        i += lit_len

        # A block legitimately ends after its final literal run.
        if i >= n:
            break

        offset = src[i] | (src[i + 1] << 8)
        i += 2
        if offset == 0:
            raise ValueError("bad LZ4 stream: zero offset")

        match_len = token & 0x0F
        if match_len == 15:
            while True:
                b = src[i]
                i += 1
                match_len += b
                if b != 255:
                    break
        match_len += 4

        start = len(dst) - offset
        if start < 0:
            raise ValueError("bad LZ4 stream: offset before start")
        # Byte-at-a-time: matches may overlap the output cursor.
        for k in range(match_len):
            dst.append(dst[start + k])

    if expected and len(dst) != expected:
        print(f"    warn: expected {expected} bytes, got {len(dst)}")
    return bytes(dst)


def read_manifest(path):
    """-> {blob_index: name}"""
    out = {}
    with open(path, "r", errors="replace") as fh:
        for line in fh:
            parts = line.split()
            # Hash32 Hash64 BlobID BlobIdx Name
            if len(parts) < 5 or not parts[0].startswith("0x"):
                continue
            try:
                out[int(parts[3])] = parts[4]
            except ValueError:
                continue
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1

    src_dir, out_dir = sys.argv[1], sys.argv[2]
    name_filter = sys.argv[3].lower() if len(sys.argv) > 3 else None

    names = read_manifest(os.path.join(src_dir, "assemblies.manifest"))
    with open(os.path.join(src_dir, "assemblies.blob"), "rb") as fh:
        blob = fh.read()

    magic, version, local_count, global_count, store_id = struct.unpack_from("<5I", blob, 0)
    if magic != XABA:
        print(f"not an AssemblyStore blob (magic=0x{magic:08x})")
        return 1
    print(f"AssemblyStore v{version}: {local_count} local, {global_count} global, "
          f"store {store_id}, {len(blob)} bytes")

    os.makedirs(out_dir, exist_ok=True)
    descriptors_at = 20  # right after the 5-word header
    written = 0

    for idx in range(local_count):
        off = descriptors_at + idx * 24
        data_off, data_size = struct.unpack_from("<2I", blob, off)
        name = names.get(idx, f"assembly_{idx:04d}")

        if name_filter and name_filter not in name.lower():
            continue
        if data_size == 0 or data_off + data_size > len(blob):
            continue

        payload = blob[data_off:data_off + data_size]
        if payload[:4] == XALZ:
            _, _desc_idx, uncompressed = struct.unpack_from("<I I I", payload, 0)
            try:
                payload = lz4_decompress_block(payload[12:], uncompressed)
            except Exception as exc:
                print(f"  ! {name}: {exc}")
                continue

        safe = name.replace("/", "_") + ".dll"
        with open(os.path.join(out_dir, safe), "wb") as fh:
            fh.write(payload)
        print(f"  [{idx:04d}] {name:<45} {len(payload):>9,} bytes")
        written += 1

    print(f"\nwrote {written} assemblies to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
