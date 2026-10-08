#! /usr/bin/env python3
#creates fixture.sqlite (ogr2ogr-sqlite style, small subset) and the
#reference fixture.senc by running the upstream tools.senc pipeline.
#
#usage:
#  PYTHONPATH=<dir with mapbox_earcut> python3 make_fixture.py
#      regenerates the synthetic fixture only
#  PYTHONPATH=... python3 make_fixture.py corpus
#      additionally downloads the NOAA cells and regenerates
#      resources/convert/corpus/<cell>/{<cell>.000,*.TXT,<cell>.sqlite.gz,
#      <cell>.senc.gz}
#
#corpus requirements:
#  - python packages: mapbox-earcut, numpy (e.g. uv venv .venv &&
#    uv pip install mapbox-earcut numpy, then run with .venv/bin/python)
#  - ogr2ogr: either on PATH or via docker image
#    ghcr.io/osgeo/gdal:ubuntu-small-latest (automatic fallback)
#  - network access to www.charts.noaa.gov
#
#note: NOAA zips also ship .001/.002 update files. GDAL's s57 reader
#applies them automatically when they sit next to the .000 - which is what
#upstream tools/tosenc.py does (extractall, then convert the .000).
#the corpus therefore keeps the update files: the generated .sqlite/.senc
#reflect the cell WITH updates applied.
import glob
import gzip
import os
import shutil
import sqlite3
import struct
import subprocess
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "resources", "convert")
CORPUS = os.path.join(OUT, "corpus")
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
sys.path.insert(0, ROOT)

GDAL_IMAGE = "ghcr.io/osgeo/gdal:ubuntu-small-latest"
CELLS = {
    #small band-5 cell: TXTDSC sidecars, holes, soundings, lines
    "US5TX12M": "https://www.charts.noaa.gov/ENCs/US5TX12M.zip",
    #band-5 harbor cell (Galveston entrance): many object classes,
    #46 TXTDSC references, multipoint soundings
    "US5TX51M": "https://www.charts.noaa.gov/ENCs/US5TX51M.zip",
}


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


def make_fixture():
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


def ogr2ogr(workdir, ifile, ofile):
    '''run the same conversion as tools/tosenc.py convertGdal().
    prefers a host ogr2ogr, falls back to the osgeo/gdal docker image.'''
    if shutil.which("ogr2ogr"):
        cmd = ["ogr2ogr", "-f", "SQLite", ofile, ifile, "-oo", "LIST_AS_STRING=ON"]
    elif shutil.which("docker"):
        cmd = ["docker", "run", "--rm",
               "--user", "%d:%d" % (os.getuid(), os.getgid()),
               "-v", workdir + ":/work", GDAL_IMAGE,
               "ogr2ogr", "-f", "SQLite",
               "/work/" + os.path.basename(ofile),
               "/work/" + os.path.basename(ifile),
               "-oo", "LIST_AS_STRING=ON"]
    else:
        raise Exception("need ogr2ogr on PATH or docker for the corpus")
    print("running:", " ".join(cmd))
    proc = subprocess.run(cmd)
    if proc.returncode != 0:
        raise Exception("ogr2ogr returned %d" % proc.returncode)


def make_corpus():
    from tools.senc import sqlite2senc

    os.makedirs(CORPUS, exist_ok=True)
    for cell, url in CELLS.items():
        cdir = os.path.join(CORPUS, cell)
        os.makedirs(cdir, exist_ok=True)
        zipname = os.path.join(cdir, cell + ".zip")
        if not os.path.exists(zipname):
            print("downloading %s" % url)
            urllib.request.urlretrieve(url, zipname)
        with zipfile.ZipFile(zipname) as zf:
            for n in zf.namelist():
                base = os.path.basename(n)
                if (base.upper().endswith(".TXT")
                        or base.upper().endswith(".000")
                        or (base.upper().startswith(cell)
                            and base.upper()[-4:-1] == ".00")):
                    if os.path.dirname(n).endswith(cell):
                        print("  extract %s" % base)
                        with zf.open(n) as src, \
                                open(os.path.join(cdir, base), "wb") as dst:
                            shutil.copyfileobj(src, dst)
        os.remove(zipname)
        src = os.path.join(cdir, cell + ".000")
        sql = os.path.join(cdir, cell + ".sqlite")
        senc = os.path.join(cdir, cell + ".senc")
        ogr2ogr(cdir, src, sql)
        options = sqlite2senc.Options()
        options.s57dir = os.path.join(ROOT, "provider", "s57static")
        options.basedir = cdir
        sqlite2senc.main(sql, senc, options)
        for fn in (sql, senc):
            with open(fn, "rb") as i, gzip.open(fn + ".gz", "wb",
                                               compresslevel=9) as o:
                shutil.copyfileobj(i, o)
            os.remove(fn)
        print("corpus %s: %d bytes" % (cell,
                                       sum(os.path.getsize(f) for f in
                                           glob.glob(cdir + "/*"))))


def main():
    make_fixture()
    if len(sys.argv) > 1 and sys.argv[1] in ("corpus", "all"):
        make_corpus()


if __name__ == "__main__":
    main()
