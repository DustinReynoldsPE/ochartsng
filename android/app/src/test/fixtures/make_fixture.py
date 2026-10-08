#! /usr/bin/env python3
#creates fixture.sqlite (ogr2ogr-sqlite style, small subset) and the
#reference fixture.senc by running the upstream tools.senc pipeline.
#usage: PYTHONPATH=<dir with mapbox_earcut> python3 make_fixture.py
import os
import sqlite3
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "resources", "convert")
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
sys.path.insert(0, ROOT)


def point(lon, lat, z=None):
    if z is None:
        return struct.pack("<BI2d", 1, 1, lon, lat)
    return struct.pack("<BI3d", 1, 0x80000001, lon, lat, z)


def multipoint(pts):
    rt = struct.pack("<BI", 1, 4) + struct.pack("<I", len(pts))
    for p in pts:
        rt += struct.pack("<BI3d", 1, 0x80000001, p[0], p[1], p[2])
    return rt


def linestring(pts):
    rt = struct.pack("<BI", 1, 2) + struct.pack("<I", len(pts))
    for p in pts:
        rt += struct.pack("<2d", p[0], p[1])
    return rt


def main():
    os.makedirs(OUT, exist_ok=True)
    dbn = os.path.join(OUT, "fixture.sqlite")
    if os.path.exists(dbn):
        os.remove(dbn)
    db = sqlite3.connect(dbn)
    db.execute(
        "CREATE TABLE geometry_columns (f_table_name VARCHAR, f_geometry_column VARCHAR, geometry_format VARCHAR)"
    )
    db.execute(
        "CREATE TABLE dsid (dsid_dsnm TEXT, dsid_edtn INTEGER, dspm_cscl INTEGER, dsid_isdt TEXT)"
    )
    db.execute(
        "INSERT INTO dsid VALUES ('TESTFIX.000',7,45000,'20240101')"
    )
    db.execute(
        "CREATE TABLE bcnlat (GEOMETRY BLOB, OBJNAM TEXT, COLOUR JSONLIST, BCNSHP INTEGER, TXTDSC TEXT)"
    )
    db.execute(
        "CREATE TABLE coalne (GEOMETRY BLOB, CATCOA INTEGER)"
    )
    db.execute(
        "CREATE TABLE soundg (GEOMETRY BLOB, SCAMIN INTEGER)"
    )
    for t in ("bcnlat", "coalne", "soundg"):
        db.execute(
            "INSERT INTO geometry_columns VALUES (?,?,'WKB')", (t, "GEOMETRY")
        )
    db.execute(
        "INSERT INTO bcnlat VALUES (?,?,?,?,?)",
        (point(8.5, 54.1), "Test Bcn", "[1,2]", 1, "note.txt"),
    )
    db.execute(
        "INSERT INTO bcnlat VALUES (?,?,?,?,?)",
        (point(8.6, 54.2), None, "[3]", 2, None),
    )
    db.execute(
        "INSERT INTO coalne VALUES (?,?)",
        (linestring([(8.5, 54.0), (8.7, 54.3), (9.0, 54.3)]), 1),
    )
    db.execute(
        "INSERT INTO soundg VALUES (?,?)",
        (point(8.55, 54.05, 12.3), 1000),
    )
    db.execute(
        "INSERT INTO soundg VALUES (?,?)",
        (multipoint([(8.56, 54.06, 5.5), (8.57, 54.07, 6.5)]), None),
    )
    db.execute(
        "INSERT INTO soundg VALUES (?,?)",
        (point(8.58, 54.08, 9.9), 1000),
    )
    db.commit()
    db.close()
    with open(os.path.join(OUT, "note.txt"), "w") as h:
        h.write("fixture note content")
    from tools.senc import sqlite2senc

    options = sqlite2senc.Options()
    options.s57dir = os.path.join(ROOT, "provider", "s57static")
    options.basedir = OUT
    sqlite2senc.main(dbn, os.path.join(OUT, "fixture.senc"), options)


if __name__ == "__main__":
    main()
