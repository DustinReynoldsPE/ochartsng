#!/usr/bin/env bash
#
# Build a minimal, statically linked ogr2ogr for Android and install it
# as libOgr2Ogr.so into android/app/src/main/jniLibs/<abi>/ so the app
# can exec it from nativeLibraryDir via ProcessBuilder (same pattern as
# liboexserverd.so, see provider/CMakeLists.txt and LibHandler.java).
#
# Driver set (minimal GDAL):
#   OGR S57 reader   (+ ISO8211 component, pulled in by the S57 driver)
#   OGR SQLite writer
#   OGR GeoJSON reader
#   MEM raster driver (always built by GDAL core)
#
# PROJ is a hard build-time dependency of GDAL (find_package(PROJ
# REQUIRED)). We build a minimal static PROJ with proj.db embedded into
# libproj (EMBED_RESOURCE_FILES + USE_ONLY_EMBEDDED_RESOURCE_FILES), so
# no runtime data files / assets are needed.
#
# sqlite3 is built from the amalgamation (the NDK exposes no libsqlite3)
# for both the target library and a host `sqlite3` CLI that PROJ needs
# (EXE_SQLITE3) to generate proj.db at build time.
#
# Usage:
#   tools/build-gdal-android.sh [arm64-v8a|armeabi-v7a ...]
#   (default: both ABIs)
#
# Environment overrides:
#   ANDROID_NDK_HOME / ANDROID_NDK  - NDK to use
#   ANDROID_SDK_ROOT               - used to find ndk/<ndkVersion>
#   OGR2OGR_WORKDIR                - build tree (default: <repo>/build/gdal-android)
#   MIN_SDK                        - default 24 (matches app minSdkVersion)
#
# Output:
#   android/app/src/main/jniLibs/<abi>/libOgr2Ogr.so   (gitignored)
#
set -euo pipefail

# ---------------- pinned versions --------------------------------------
GDAL_VERSION=3.13.3          # latest stable, released 2026-08-18
PROJ_VERSION=9.9.0           # latest stable, released 2026-09-15
SQLITE_AMALG=sqlite-amalgamation-3530400
SQLITE_URL="https://sqlite.org/2026/${SQLITE_AMALG}.zip"
GDAL_URL="https://download.osgeo.org/gdal/${GDAL_VERSION}/gdal-${GDAL_VERSION}.tar.xz"
PROJ_URL="https://download.osgeo.org/proj/proj-${PROJ_VERSION}.tar.gz"

MIN_SDK=${MIN_SDK:-24}
NDK_VERSION_EXPECTED=29.0.14206865   # ndkVersion in android/app/build.gradle

REPO_DIR=$(cd "$(dirname "$0")/.." && pwd)
WORKDIR=${OGR2OGR_WORKDIR:-$REPO_DIR/build/gdal-android}
SRC_DIR=$WORKDIR/src
HOST_DIR=$WORKDIR/host
JNILIBS_DIR=$REPO_DIR/android/app/src/main/jniLibs

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a)
fi

# ---------------- locate tools ------------------------------------------
find_ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        echo "$ANDROID_NDK_HOME"; return
    fi
    if [ -n "${ANDROID_NDK:-}" ] && [ -d "$ANDROID_NDK" ]; then
        echo "$ANDROID_NDK"; return
    fi
    for base in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        if [ -d "$base/ndk/$NDK_VERSION_EXPECTED" ]; then
            echo "$base/ndk/$NDK_VERSION_EXPECTED"; return
        fi
        # fall back to newest installed NDK
        local newest
        newest=$(ls -1 "$base/ndk" 2>/dev/null | sort -V | tail -1)
        if [ -n "$newest" ]; then
            echo "$base/ndk/$newest"; return
        fi
    done
    return 1
}

NDK=$(find_ndk) || { echo "error: no Android NDK found" >&2; exit 1; }
TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake
NDK_BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
[ -f "$TOOLCHAIN_FILE" ] || { echo "error: $TOOLCHAIN_FILE missing" >&2; exit 1; }
echo "Using NDK: $NDK"

NINJA=$(command -v ninja || true)
if [ -z "$NINJA" ]; then
    for c in "$HOME/Android/Sdk/cmake"/*/bin/ninja "${ANDROID_SDK_ROOT:-/nonexistent}/cmake"/*/bin/ninja; do
        [ -x "$c" ] && NINJA=$c && break
    done
fi
GENERATOR="Unix Makefiles"
[ -n "$NINJA" ] && GENERATOR="Ninja"
echo "Using generator: $GENERATOR ($NINJA)"

JOBS=$(nproc)

# ---------------- fetch sources -----------------------------------------
mkdir -p "$SRC_DIR" "$HOST_DIR/bin"
cd "$SRC_DIR"

fetch() { # url file
    if [ ! -s "$2" ]; then
        echo "downloading $1"
        curl -fL --retry 3 -o "$2" "$1"
    fi
}

fetch "$GDAL_URL" "gdal-${GDAL_VERSION}.tar.xz"
fetch "$PROJ_URL" "proj-${PROJ_VERSION}.tar.gz"
fetch "$SQLITE_URL" "${SQLITE_AMALG}.zip"

[ -d "gdal-${GDAL_VERSION}" ] || tar xf "gdal-${GDAL_VERSION}.tar.xz"
[ -d "proj-${PROJ_VERSION}" ] || tar xf "proj-${PROJ_VERSION}.tar.gz"
if [ ! -d "$SQLITE_AMALG" ]; then
    unzip -o -q "${SQLITE_AMALG}.zip"
fi

# ---------------- host sqlite3 CLI (for proj.db generation) --------------
HOST_SQLITE3=$HOST_DIR/bin/sqlite3
if [ ! -x "$HOST_SQLITE3" ]; then
    echo "building host sqlite3 CLI"
    gcc -O2 -o "$HOST_SQLITE3" \
        "$SRC_DIR/$SQLITE_AMALG/shell.c" "$SRC_DIR/$SQLITE_AMALG/sqlite3.c" \
        -DSQLITE_THREADSAFE=0 -lpthread -ldl -lm
fi

# ---------------- per-ABI build ------------------------------------------
abi_clang_triple() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android${MIN_SDK}" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi${MIN_SDK}" ;;
        *) echo "unsupported ABI $1" >&2; exit 1 ;;
    esac
}

for ABI in "${ABIS[@]}"; do
    echo "==================== $ABI ===================="
    PREFIX=$WORKDIR/prefix/$ABI
    TRIPLE=$(abi_clang_triple "$ABI")
    CC="$NDK_BIN/${TRIPLE}-clang"
    CXX="$NDK_BIN/${TRIPLE}-clang++"
    AR="$NDK_BIN/llvm-ar"
    STRIP="$NDK_BIN/llvm-strip"
    READELF="$NDK_BIN/llvm-readelf"

    mkdir -p "$PREFIX/include" "$PREFIX/lib"

    # ---- sqlite3 target library (amalgamation) ----
    SQLITE_BUILD=$WORKDIR/build/$ABI/sqlite
    mkdir -p "$SQLITE_BUILD"
    if [ ! -f "$PREFIX/lib/libsqlite3.a" ]; then
        echo "[$ABI] building sqlite3"
        "$CC" -O2 -fPIC -c "$SRC_DIR/$SQLITE_AMALG/sqlite3.c" \
            -o "$SQLITE_BUILD/sqlite3.o" \
            -DSQLITE_THREADSAFE=1 \
            -DSQLITE_ENABLE_RTREE \
            -DSQLITE_ENABLE_COLUMN_METADATA \
            -DSQLITE_ENABLE_FTS5 \
            -DSQLITE_OMIT_LOAD_EXTENSION
        "$AR" rcs "$PREFIX/lib/libsqlite3.a" "$SQLITE_BUILD/sqlite3.o"
        cp "$SRC_DIR/$SQLITE_AMALG/sqlite3.h" "$SRC_DIR/$SQLITE_AMALG/sqlite3ext.h" \
            "$PREFIX/include/"
    fi

    # common cmake args: NDK toolchain + never pick up host libraries
    CMAKE_COMMON=(
        -G "$GENERATOR"
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE"
        -DANDROID_ABI="$ABI"
        -DANDROID_PLATFORM="android-$MIN_SDK"
        -DANDROID_STL=c++_static
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX="$PREFIX"
        -DCMAKE_PREFIX_PATH="$PREFIX"
        -DCMAKE_FIND_ROOT_PATH="$PREFIX"
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=NEVER
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=NEVER
        -DCMAKE_FIND_USE_CMAKE_SYSTEM_PATH=NO
        -DSQLite3_INCLUDE_DIR="$PREFIX/include"
        -DSQLite3_LIBRARY="$PREFIX/lib/libsqlite3.a"
        -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections"
        -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
        -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,--gc-sections"
    )
    [ -n "$NINJA" ] && CMAKE_COMMON+=( -DCMAKE_MAKE_PROGRAM="$NINJA" )

    # ---- PROJ (static, proj.db embedded) ----
    PROJ_BUILD=$WORKDIR/build/$ABI/proj
    if [ ! -f "$PREFIX/lib/libproj.a" ]; then
        echo "[$ABI] configuring PROJ $PROJ_VERSION"
        mkdir -p "$PROJ_BUILD"
        cmake -S "$SRC_DIR/proj-${PROJ_VERSION}" -B "$PROJ_BUILD" \
            "${CMAKE_COMMON[@]}" \
            -DBUILD_SHARED_LIBS=OFF \
            -DBUILD_APPS=OFF \
            -DBUILD_TESTING=OFF \
            -DBUILD_EXAMPLES=OFF \
            -DENABLE_TIFF=OFF \
            -DENABLE_CURL=OFF \
            -DBUILD_PROJSYNC=OFF \
            -DEMBED_RESOURCE_FILES=ON \
            -DUSE_ONLY_EMBEDDED_RESOURCE_FILES=ON \
            -DEMBED_PROJ_DATA_PATH=OFF \
            -DEXE_SQLITE3="$HOST_SQLITE3"
        echo "[$ABI] building PROJ"
        cmake --build "$PROJ_BUILD" --parallel "$JOBS"
        cmake --install "$PROJ_BUILD"
    fi

    # ---- GDAL (static) + ogr2ogr app ----
    GDAL_BUILD=$WORKDIR/build/$ABI/gdal
    echo "[$ABI] configuring GDAL $GDAL_VERSION"
    mkdir -p "$GDAL_BUILD"
    cmake -S "$SRC_DIR/gdal-${GDAL_VERSION}" -B "$GDAL_BUILD" \
        "${CMAKE_COMMON[@]}" \
        -DBUILD_SHARED_LIBS=OFF \
        -DBUILD_APPS=ON \
        -DBUILD_TESTING=OFF \
        -DBUILD_JAVA_BINDINGS=OFF \
        -DBUILD_PYTHON_BINDINGS=OFF \
        -DBUILD_CSHARP_BINDINGS=OFF \
        -DENABLE_GNM=OFF \
        -DGDAL_BUILD_OPTIONAL_DRIVERS=OFF \
        -DOGR_BUILD_OPTIONAL_DRIVERS=OFF \
        -DOGR_ENABLE_DRIVER_S57=ON \
        -DOGR_ENABLE_DRIVER_SQLITE=ON \
        -DOGR_ENABLE_DRIVER_GEOJSON=ON \
        -DGDAL_USE_ZLIB_INTERNAL=ON \
        -DGDAL_USE_OPENMP=OFF \
        -DGDAL_USE_CURL=OFF \
        -DGDAL_USE_ICONV=OFF \
        -DGDAL_USE_EXPAT=OFF \
        -DGDAL_USE_XERCESC=OFF \
        -DGDAL_USE_LIBXML2=OFF \
        -DGDAL_USE_GEOS=OFF \
        -DGDAL_USE_HDF5=OFF \
        -DGDAL_USE_NETCDF=OFF \
        -DGDAL_USE_OPENSSL=OFF \
        -DGDAL_USE_CRYPTOPP=OFF \
        -DGDAL_USE_SPATIALITE=OFF \
        -DGDAL_USE_PCRE=OFF \
        -DGDAL_USE_PCRE2=OFF \
        -DGDAL_USE_ODBC=OFF \
        -DGDAL_USE_POSTGRESQL=OFF \
        -DGDAL_USE_MYSQL=OFF \
        -DGDAL_USE_LZ4=OFF \
        -DGDAL_USE_BLOSC=OFF \
        -DGDAL_USE_ZSTD=OFF \
        -DGDAL_USE_DEFLATE=OFF \
        -DGDAL_USE_WEBP=OFF \
        -DGDAL_USE_OPENJPEG=OFF \
        -DSFCGAL_CONFIG=disabled \
        -DHDF5_C_COMPILER_EXECUTABLE=disabled \
        -DHDF5_CXX_COMPILER_EXECUTABLE=disabled

    echo "[$ABI] building ogr2ogr"
    cmake --build "$GDAL_BUILD" --target ogr2ogr --parallel "$JOBS"

    # ---- install as libOgr2Ogr.so into jniLibs ----
    EXE="$GDAL_BUILD/apps/ogr2ogr"
    [ -f "$EXE" ] || { echo "error: $EXE not produced" >&2; exit 1; }
    DEST_DIR="$JNILIBS_DIR/$ABI"
    mkdir -p "$DEST_DIR"
    "$STRIP" -o "$DEST_DIR/libOgr2Ogr.so" "$EXE"
    chmod 755 "$DEST_DIR/libOgr2Ogr.so"

    echo "[$ABI] installed $DEST_DIR/libOgr2Ogr.so"
    ls -l "$DEST_DIR/libOgr2Ogr.so"

    # ---- verification: 16KB page alignment + DT_NEEDED ----
    echo "[$ABI] LOAD segment alignment:"
    "$READELF" -lW "$DEST_DIR/libOgr2Ogr.so" | grep -E "LOAD|Type" | head -8
    echo "[$ABI] DT_NEEDED:"
    "$READELF" -dW "$DEST_DIR/libOgr2Ogr.so" | grep NEEDED || echo "  (none)"
done

echo "done. Binaries in $JNILIBS_DIR"
