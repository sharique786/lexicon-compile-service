#!/usr/bin/env python3
"""
inspect_hdb.py — dump the human-readable contents of a Hyperscan .hdb file

This reads files produced by com.gliwka.hyperscan.wrapper.Database.save(),
which is what LexiconCompileBundleService / HyperscanCompiler.compileCombinedDatabase()
uses to write the .hdb file in the compile-bundle zip.

WHY THIS WORKS WITHOUT THE NATIVE HYPERSCAN LIBRARY:
Database.save() writes two distinct sections into the same file:
  1. A metadata header — plain Java primitives and strings: for every
     expression, its id, its original pattern text, and its Hyperscan
     flag bitmasks. This is genuinely just data, not compiled anything.
  2. The opaque native database — the actual compiled DFA/NFA tables
     that Hyperscan's vectorized scanning engine uses. This is
     conceptually similar to compiled machine code: there's no
     "decompiling" it back into the original regex, and it's
     platform-specific (see hs_serialize_database in Intel's docs).

This script parses ONLY section 1. It reports the byte range and size of
section 2 but does not attempt to interpret it, because it isn't possible to.

USAGE:
    python3 inspect_hdb.py rule.hdb
    python3 inspect_hdb.py rule.hdb --json
"""

import struct
import sys
import json
import argparse

# Mirrors com.gliwka.hyperscan.wrapper.ExpressionFlag exactly (bit values from
# the real library source, not guessed).
FLAG_NAMES = {
    0:    "NO_FLAG",
    1:    "CASELESS",
    2:    "DOTALL",
    4:    "MULTILINE",
    8:    "SINGLEMATCH",
    16:   "ALLOWEMPTY",
    32:   "UTF8",
    64:   "UCP",
    128:  "PREFILTER",
    256:  "SOM_LEFTMOST",
    512:  "COMBINATION",
    1024: "QUIET",
}


def read_modified_utf8(buf: bytes, offset: int) -> tuple[str, int]:
    """
    Reads one string in Java's DataOutputStream.writeUTF format:
    a 2-byte big-endian length (BYTE count, not character count) followed
    by that many bytes of modified-UTF-8.

    For every character actually produced by TermSyntaxTranslator (ASCII,
    Latin, Arabic, Hebrew, Korean, Chinese, Japanese — all within the
    Unicode Basic Multilingual Plane, since emoji are converted to ASCII
    \\x{NNNN} escapes before serialization) this is byte-identical to
    standard UTF-8, so a plain UTF-8 decode is exact for this project's
    patterns. True modified-UTF-8 only diverges from standard UTF-8 for
    an embedded NUL byte or a raw supplementary-plane character — neither
    of which this codebase's translator ever emits into a pattern string.
    """
    if offset + 2 > len(buf):
        raise ValueError(f"Truncated file: cannot read string length at offset {offset}")
    (length,) = struct.unpack_from('>H', buf, offset)
    offset += 2
    if offset + length > len(buf):
        raise ValueError(f"Truncated file: string claims {length} bytes but only "
                         f"{len(buf) - offset} remain at offset {offset}")
    text = buf[offset:offset + length].decode('utf-8')
    return text, offset + length


def decode_flags(bitmask_list):
    names = [FLAG_NAMES.get(b, f"UNKNOWN(0x{b:x})") for b in bitmask_list]
    combined = 0
    for b in bitmask_list:
        combined |= b
    return names, combined


def parse_hdb(path: str) -> dict:
    with open(path, 'rb') as f:
        buf = f.read()

    offset = 0
    if len(buf) < 4:
        raise ValueError("File too short to be a valid .hdb (need at least 4 bytes)")

    (expression_count,) = struct.unpack_from('>i', buf, offset)
    offset += 4

    if expression_count < 0 or expression_count > 1_000_000:
        raise ValueError(
            f"expressionCount={expression_count} looks implausible — this probably "
            f"isn't a Database.save() file, or it's corrupted/truncated.")

    expressions = []
    for i in range(expression_count):
        (expr_id,) = struct.unpack_from('>i', buf, offset)
        offset += 4
        pattern, offset = read_modified_utf8(buf, offset)
        (flag_count,) = struct.unpack_from('>i', buf, offset)
        offset += 4
        bitmasks = []
        for _ in range(flag_count):
            (bm,) = struct.unpack_from('>i', buf, offset)
            offset += 4
            bitmasks.append(bm)
        flag_names, combined_bitmask = decode_flags(bitmasks)
        expressions.append({
            "id": None if expr_id == -1 else expr_id,
            "pattern": pattern,
            "flags": flag_names,
            "flagBitmask": combined_bitmask,
        })

    (native_blob_length,) = struct.unpack_from('>i', buf, offset)
    offset += 4
    native_blob_start = offset
    native_blob_end = offset + native_blob_length

    trailing = len(buf) - native_blob_end

    return {
        "file": path,
        "fileSizeBytes": len(buf),
        "expressionCount": expression_count,
        "expressions": expressions,
        "nativeDatabase": {
            "note": "Opaque compiled Hyperscan DFA/NFA tables — not human-readable, "
                    "not portable across CPU architectures. This is the part the "
                    "Lexicon Scan Engine actually loads via Database.load() to scan "
                    "messages; everything above is metadata only.",
            "byteRange": [native_blob_start, native_blob_end],
            "sizeBytes": native_blob_length,
        },
        "trailingBytesAfterDatabase": trailing,  # should be 0 for a well-formed file
    }


def print_human(result: dict):
    print(f"File: {result['file']}  ({result['fileSizeBytes']:,} bytes total)")
    print(f"Expressions: {result['expressionCount']}")
    print()
    for e in result["expressions"]:
        id_str = str(e["id"]) if e["id"] is not None else "(none)"
        print(f"  [id={id_str}] flags={'+'.join(e['flags'])} (0x{e['flagBitmask']:x})")
        print(f"      pattern: {e['pattern']}")
        print()
    nd = result["nativeDatabase"]
    print(f"Native Hyperscan database: {nd['sizeBytes']:,} bytes "
          f"(offset {nd['byteRange'][0]}–{nd['byteRange'][1]}) — opaque, not readable")
    if result["trailingBytesAfterDatabase"] != 0:
        print(f"WARNING: {result['trailingBytesAfterDatabase']} unexpected trailing "
              f"bytes after the native database — file may be corrupted or appended to.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hdb_file", help="Path to the .hdb file")
    parser.add_argument("--json", action="store_true",
                        help="Output machine-readable JSON instead of a human-readable summary")
    args = parser.parse_args()

    try:
        result = parse_hdb(args.hdb_file)
    except (ValueError, struct.error) as e:
        print(f"Could not parse '{args.hdb_file}' as a Database.save() file: {e}",
              file=sys.stderr)
        sys.exit(1)

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print_human(result)


if __name__ == "__main__":
    main()