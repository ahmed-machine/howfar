#!/bin/bash
#
# Download all GTFS feeds for NY/NJ/CT/PA transit agencies
#
# This script downloads GTFS feeds and places them in the otp-data directory
# for OpenTripPlanner to consume. Feeds are stored in feeds/ and also copied
# to the OTP data root (where OTP picks them up during graph build).
#

set -e

OTP_DATA_DIR="${OTP_DATA_DIR:-./otp-data}"
GTFS_DIR="${OTP_DATA_DIR}/feeds"

mkdir -p "$GTFS_DIR"

echo "Downloading GTFS feeds to $GTFS_DIR..."

# Function to download and verify GTFS
download_gtfs() {
    local name=$1
    local url=$2
    local filename="${name}.gtfs.zip"

    # Skip if file already exists and is a valid zip
    if [ -f "$GTFS_DIR/$filename" ] && unzip -t "$GTFS_DIR/$filename" >/dev/null 2>&1; then
        echo "  [ok] $name already exists, skipping"
        return
    fi

    echo "Downloading $name..."
    if curl -L -o "$GTFS_DIR/$filename" "$url" 2>/dev/null; then
        # Verify it's a valid zip with agency.txt at root
        if unzip -t "$GTFS_DIR/$filename" >/dev/null 2>&1; then
            if zipinfo -1 "$GTFS_DIR/$filename" 2>/dev/null | grep -q "^agency.txt$"; then
                echo "  [ok] $name downloaded successfully"
            else
                echo "  [WARN] $name downloaded but missing agency.txt at root (nested zip?)"
            fi
        else
            echo "  [FAIL] $name download failed (invalid zip)"
            rm -f "$GTFS_DIR/$filename"
        fi
    else
        echo "  [FAIL] $name download failed"
    fi
}

# Function to download and unpack a nested zip-of-zips (e.g. SEPTA)
download_gtfs_nested() {
    local prefix=$1
    local url=$2
    shift 2
    local inner_names=("$@")

    # Skip if all inner files already exist
    local all_exist=true
    for inner in "${inner_names[@]}"; do
        if [ ! -f "$GTFS_DIR/${prefix}-${inner}.gtfs.zip" ]; then
            all_exist=false
            break
        fi
    done
    if $all_exist; then
        echo "  [ok] $prefix feeds already exist, skipping"
        return
    fi

    echo "Downloading $prefix (nested archive)..."
    local tmpfile
    tmpfile=$(mktemp)
    if curl -L -o "$tmpfile" "$url" 2>/dev/null; then
        local tmpdir
        tmpdir=$(mktemp -d)
        unzip -o "$tmpfile" -d "$tmpdir" >/dev/null 2>&1
        for inner in "${inner_names[@]}"; do
            # Find the matching zip inside
            local found
            found=$(find "$tmpdir" -name "*${inner}*" -type f | head -1)
            if [ -n "$found" ]; then
                cp "$found" "$GTFS_DIR/${prefix}-${inner}.gtfs.zip"
                echo "  [ok] ${prefix}-${inner} extracted"
            else
                echo "  [FAIL] ${prefix}-${inner} not found in archive"
            fi
        done
        rm -rf "$tmpdir"
    else
        echo "  [FAIL] $prefix download failed"
    fi
    rm -f "$tmpfile"
}

echo ""
echo "=== New York ==="

# MTA Subway
download_gtfs "mta-subway" "http://web.mta.info/developers/data/nyct/subway/google_transit.zip"

# MTA Bus (NYC Transit)
download_gtfs "mta-bus-bronx" "http://web.mta.info/developers/data/nyct/bus/google_transit_bronx.zip"
download_gtfs "mta-bus-brooklyn" "http://web.mta.info/developers/data/nyct/bus/google_transit_brooklyn.zip"
download_gtfs "mta-bus-manhattan" "http://web.mta.info/developers/data/nyct/bus/google_transit_manhattan.zip"
download_gtfs "mta-bus-queens" "http://web.mta.info/developers/data/nyct/bus/google_transit_queens.zip"
download_gtfs "mta-bus-staten-island" "http://web.mta.info/developers/data/nyct/bus/google_transit_staten_island.zip"

# MTA Bus Company (express buses)
download_gtfs "mta-bus-company" "http://web.mta.info/developers/data/busco/google_transit.zip"

# Long Island Rail Road
download_gtfs "lirr" "http://web.mta.info/developers/data/lirr/google_transit.zip"

# Metro-North Railroad
download_gtfs "metro-north" "http://web.mta.info/developers/data/mnr/google_transit.zip"

# NYC Ferry
download_gtfs "nyc-ferry" "https://nycferry.connexionz.net/rtt/public/utility/gtfs.aspx"

echo ""
echo "=== New Jersey ==="

# NJ Transit
download_gtfs "nj-transit-rail" "https://www.njtransit.com/rail_data.zip"
download_gtfs "nj-transit-bus" "https://www.njtransit.com/bus_data.zip"

# PATH
download_gtfs "path" "https://github.com/transitland/gtfs-archives-not-hosted-elsewhere/raw/master/path-nj-us.zip"

echo ""
echo "=== Connecticut ==="

# CT Transit (Hartford, New Haven, Stamford, Waterbury, etc.)
download_gtfs "ct-transit" "https://www.cttransit.com/sites/default/files/gtfs/googlect_transit.zip"

echo ""
echo "=== National ==="

# Amtrak
download_gtfs "amtrak" "https://content.amtrak.com/content/gtfs/GTFS.zip"

echo ""
echo "=== Pennsylvania ==="

# SEPTA (nested zip containing google_bus.zip and google_rail.zip)
download_gtfs_nested "septa" \
    "https://github.com/septadev/GTFS/releases/latest/download/gtfs_public.zip" \
    "bus" "rail"

# Copy all feeds to OTP data directory (OTP picks up .zip files from root)
echo ""
echo "Copying feeds to OTP data directory..."
cp "$GTFS_DIR"/*.gtfs.zip "$OTP_DATA_DIR/" 2>/dev/null || true

echo ""
echo "GTFS download complete!"
echo ""
echo "Feeds in $GTFS_DIR:"
ls -lh "$GTFS_DIR"/*.gtfs.zip 2>/dev/null
FEED_COUNT=$(ls "$GTFS_DIR"/*.gtfs.zip 2>/dev/null | wc -l | tr -d ' ')
echo ""
echo "$FEED_COUNT feed(s) total"
echo ""
echo "Run build-otp-graph.sh to rebuild the OTP graph."
