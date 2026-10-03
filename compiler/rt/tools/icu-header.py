#!/usr/bin/env python3
"""Turns an ICU .dat file into the C header the runtime links in.

    compiler/rt/tools/icu-header.py [--check]

Reads   compiler/rt/android/external/icu/icu4c/source/data/icudt68l.dat
Writes  compiler/rt/android/libcore.luni4/src/main/native/icudt68l.dat.gz.h

Why a header and not a file beside the binary
---------------------------------------------
`android_icu_impl_ICUBinary.cpp` has two ways to find its data, and takes the first that answers:

    if (findCustomICUData(path))  icu_data_ = IcuDataMap::Create(path);   // a file, mmapped
    else                          icu_data_ = IcuDataGzip::Create();      // this header, inflated

`findCustomICUData` only works on Apple platforms — everywhere else it is a hard-coded
"/usr/share/icu" with a TODO beside it. So on a phone the second way is the one that runs, on both
of them, and it needs no file system, no bundle resource and no -rvm:ResourcesPath=. One mechanism
for iOS, Android, macOS and Linux.

The cost is that the data is malloc'd and inflated at startup rather than paged in from a mapping:
about 10 MB of heap that stays for the life of the process. Measured against the alternative — two
different resource-copying routes, one per platform, and a lookup that is a TODO on the one that
matters — that is the cheaper side.

Why the header is generated and not checked in
----------------------------------------------
As C source the array is six bytes of text per byte of data: 4.4 MB of gzip becomes 26 MB of
.h file. The gzip itself is checked in instead (icudt68l.dat.gz, beside the header's directory),
and this script expands it. `--check` verifies the header matches without writing, which is what
the build uses.
"""
import gzip
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
DAT = HERE / ".." / "android" / "external" / "icu" / "icu4c" / "source" / "data" / "icudt68l.dat"
GZ = HERE / ".." / "android" / "libcore.luni4" / "src" / "main" / "native" / "icudt68l.dat.gz"
HEADER = GZ.with_suffix(".gz.h")


def render(raw: bytes, packed: bytes) -> str:
    """The same shape the checked-in header had: xxd -i output plus the two lengths."""
    out = ["unsigned char icudt68l_dat_gz[] = {"]
    for start in range(0, len(packed), 12):
        row = ", ".join(f"0x{byte:02x}" for byte in packed[start:start + 12])
        out.append(f"  {row},")
    # The last row carries no trailing comma, as xxd writes it.
    out[-1] = out[-1][:-1]
    out.append("};")
    out.append(f"unsigned int icudt68l_dat_gz_len = {len(packed)};")
    # IcuDataGzip mallocs this much and fails loudly if inflate returns anything else, so it has
    # to be the uncompressed length and not an estimate.
    out.append(f"unsigned int icudt68l_dat_len = {len(raw)};")
    return "\n".join(out) + "\n"


def main() -> int:
    checking = "--check" in sys.argv

    # The .dat wins when it is there. It is what icu-data.sh has just written, and taking the .gz
    # instead would silently keep the previous data — which is exactly the shape of the stale
    # artefact that cost a day on 02.10.2026. A fresh clone has only the .gz and uses it.
    if DAT.exists():
        raw = DAT.read_bytes()
        # mtime=0 so that the same .dat always gives the same .gz — otherwise every rebuild is a
        # 4 MB diff with no change in it.
        packed = gzip.compress(raw, compresslevel=9, mtime=0)
        if not checking and (not GZ.exists() or GZ.read_bytes() != packed):
            GZ.write_bytes(packed)
            print(f"   wrote {GZ.name}, {len(packed):,} bytes")
    elif GZ.exists():
        packed = GZ.read_bytes()
        raw = gzip.decompress(packed)
    else:
        print(f"Neither {GZ} nor {DAT} exists.", file=sys.stderr)
        print("Run compiler/rt/tools/icu-data.sh to build the data file.", file=sys.stderr)
        return 1

    wanted = render(raw, packed)

    if checking:
        if HEADER.exists() and HEADER.read_text() == wanted:
            print(f"   {HEADER.name} is current")
            return 0
        print(f"{HEADER.name} does not match {GZ.name}. Run compiler/rt/tools/icu-header.py.",
              file=sys.stderr)
        return 1

    HEADER.write_text(wanted)
    print(f"   {len(raw):,} bytes of ICU data -> {len(packed):,} gzipped "
          f"-> {HEADER.stat().st_size:,} of header")
    print(f"   {HEADER}")
    print()
    print("Rebuild the native runtime for it to take effect:")
    print("   compiler/vm/build.sh --target=macosx-arm64 --build=release")
    return 0


if __name__ == "__main__":
    sys.exit(main())
