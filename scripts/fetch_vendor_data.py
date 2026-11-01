#!/usr/bin/env python3
"""Builds the two manufacturer-name tables the app bundles as assets:

  app/src/main/assets/vendors.tsv     MAC prefix (6/7/9 hex digits) <TAB> organisation name
  app/src/main/assets/companies.tsv   Bluetooth company id (decimal) <TAB> company name

Sources (public registries, downloaded when you run this script):
  IEEE Registration Authority  https://standards-oui.ieee.org/oui/oui.csv
                               https://standards-oui.ieee.org/oui28/mam.csv
                               https://standards-oui.ieee.org/oui36/oui36.csv
  Bluetooth SIG                https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml

The files are not stored in this repository because their redistribution terms were not confirmed.
Check the publishers' terms of use before redistributing the generated tables.

Usage:
  python3 scripts/fetch_vendor_data.py                  download and write into app/src/main/assets
  python3 scripts/fetch_vendor_data.py --from-dir DIR   use already downloaded files (oui.csv, mam.csv, oui36.csv,
                                                         company_identifiers.yaml in DIR) instead of downloading
  python3 scripts/fetch_vendor_data.py --out DIR        write the tables into DIR instead of the assets folder
"""
import argparse
import csv
import hashlib
import io
import re
import sys
import urllib.request
from pathlib import Path

IEEE = [
    ("oui.csv", "https://standards-oui.ieee.org/oui/oui.csv"),
    ("mam.csv", "https://standards-oui.ieee.org/oui28/mam.csv"),
    ("oui36.csv", "https://standards-oui.ieee.org/oui36/oui36.csv"),
]
SIG = (
    "company_identifiers.yaml",
    "https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml",
)
HEX = re.compile(r"^[0-9A-F]+$")


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "deja-scan-data-script"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def one_line(text: str) -> str:
    return " ".join(text.split())


def parse_ieee(raw: bytes):
    rows, bad = [], 0
    reader = csv.reader(io.StringIO(raw.decode("utf-8-sig")))
    next(reader, None)  # header
    for row in reader:
        if len(row) < 3:
            bad += 1
            continue
        prefix, name = row[1].strip().upper(), one_line(row[2])
        if len(prefix) not in (6, 7, 9) or not HEX.match(prefix) or not name:
            bad += 1
            continue
        rows.append((prefix, name))
    return rows, bad


def unquote(v: str) -> str:
    v = v.strip()
    if len(v) >= 2 and v[0] == "'" and v[-1] == "'":
        return v[1:-1].replace("''", "'")
    if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
        return bytes(v[1:-1], "utf-8").decode("unicode_escape").encode("latin-1", "ignore").decode("utf-8", "ignore") \
            if "\\" in v else v[1:-1]
    return v


def parse_sig(raw: bytes):
    out, cur = [], None
    for line in raw.decode("utf-8-sig").splitlines():
        m = re.match(r"^\s*-\s*value:\s*(0x[0-9A-Fa-f]+)\s*$", line)
        if m:
            cur = int(m.group(1), 16)
            continue
        m = re.match(r"^\s+name:\s*(.*)$", line)
        if m and cur is not None:
            name = one_line(unquote(m.group(1)))
            if name:
                out.append((cur, name))
            cur = None
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--from-dir", type=Path)
    ap.add_argument("--out", type=Path)
    args = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    out_dir = args.out or root / "app" / "src" / "main" / "assets"
    out_dir.mkdir(parents=True, exist_ok=True)

    def get(name: str, url: str) -> bytes:
        if args.from_dir:
            return (args.from_dir / name).read_bytes()
        print(f"downloading {url}")
        return fetch(url)

    prefixes, bad_total = [], 0
    for name, url in IEEE:
        raw = get(name, url)
        rows, bad = parse_ieee(raw)
        print(f"{name}: {len(rows)} records, {bad} skipped, sha256 {hashlib.sha256(raw).hexdigest()}")
        prefixes += rows
        bad_total += bad
    raw = get(*SIG)
    companies = parse_sig(raw)
    print(f"{SIG[0]}: {len(companies)} records, sha256 {hashlib.sha256(raw).hexdigest()}")
    if not prefixes or not companies:
        print("no records parsed: the source format may have changed", file=sys.stderr)
        return 1

    (out_dir / "vendors.tsv").write_text("".join(f"{p}\t{n}\n" for p, n in prefixes), encoding="utf-8")
    (out_dir / "companies.tsv").write_text("".join(f"{i}\t{n}\n" for i, n in companies), encoding="utf-8")
    print(f"wrote {out_dir / 'vendors.tsv'} and {out_dir / 'companies.tsv'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
