#!/bin/bash
#
# Build OpenTripPlanner 2.x graph from GTFS feeds and OSM data
#
# This script:
# 1. Downloads OTP 2.5.0 JAR if not present
# 2. Downloads OSM extracts for NY, NJ, CT, PA if not present
# 3. Builds the OTP graph with all GTFS feeds
#
# OTP 2.5 requires Java 21+ and uses a different graph format than 1.x

set -e

OTP_DATA_DIR="${OTP_DATA_DIR:-./otp-data}"
OTP_VERSION="2.5.0"
OTP_JAR="$OTP_DATA_DIR/otp-${OTP_VERSION}-shaded.jar"

mkdir -p "$OTP_DATA_DIR"

echo "Building OpenTripPlanner ${OTP_VERSION} graph..."

# Download OTP 2.5.0 JAR if not present
if [ ! -f "$OTP_JAR" ]; then
    echo "Downloading OpenTripPlanner ${OTP_VERSION}..."
    curl -L -o "$OTP_JAR" "https://repo1.maven.org/maven2/org/opentripplanner/otp/${OTP_VERSION}/otp-${OTP_VERSION}-shaded.jar"
    echo "OTP download complete"
else
    echo "OTP JAR already exists: $OTP_JAR"
fi

# Download OSM extracts for each state if not present
OSM_STATES=("new-york" "new-jersey" "connecticut" "pennsylvania")

for state in "${OSM_STATES[@]}"; do
    osm_file="$OTP_DATA_DIR/${state}-latest.osm.pbf"
    if [ ! -f "$osm_file" ]; then
        echo "Downloading ${state} OSM extract..."
        curl -L -o "$osm_file" "https://download.geofabrik.de/north-america/us/${state}-latest.osm.pbf"
        echo "${state} OSM download complete"
    else
        echo "OSM file already exists: $osm_file"
    fi
done

# Check for GTFS files (stored in 'feeds' subdirectory)
GTFS_DIR="$OTP_DATA_DIR/feeds"

if [ ! -d "$GTFS_DIR" ] || [ -z "$(ls -A $GTFS_DIR 2>/dev/null)" ]; then
    echo "No GTFS files found. Run import-gtfs.sh first."
    exit 1
fi

echo ""
echo "GTFS feeds found:"
ls -lh "$GTFS_DIR"/*.zip 2>/dev/null

# Copy GTFS files to OTP data directory (OTP picks up all .zip files)
echo ""
echo "Copying GTFS feeds to OTP data directory..."
cp "$GTFS_DIR"/*.zip "$OTP_DATA_DIR/" 2>/dev/null || true

# Count GTFS files
GTFS_COUNT=$(ls "$OTP_DATA_DIR"/*.zip 2>/dev/null | wc -l | tr -d ' ')
echo "Found $GTFS_COUNT GTFS feed(s)"

# Count OSM files
OSM_COUNT=$(ls "$OTP_DATA_DIR"/*.osm.pbf 2>/dev/null | wc -l | tr -d ' ')
echo "Found $OSM_COUNT OSM extract(s)"

# Remove old graph if it exists
if [ -f "$OTP_DATA_DIR/graph.obj" ] || [ -d "$OTP_DATA_DIR/default" ]; then
    echo "Removing old graph..."
    rm -f "$OTP_DATA_DIR/graph.obj"
    rm -rf "$OTP_DATA_DIR/default"
fi

# Build graph using OTP 2.x
# OTP 2.x command: --build --save <directory>
echo ""
echo "Building OTP 2.x graph..."
echo "Using OTP ${OTP_VERSION} with $OSM_COUNT OSM extracts and $GTFS_COUNT GTFS feeds"

java -Xmx32g -jar "$OTP_JAR" --build --save "$OTP_DATA_DIR"

# Check for graph file (OTP 2.x creates graph.obj in the data directory)
if [ -f "$OTP_DATA_DIR/graph.obj" ]; then
    # Clean up copied GTFS files (originals remain in feeds/)
    echo "Cleaning up temporary GTFS copies..."
    rm -f "$OTP_DATA_DIR"/*.zip

    echo ""
    echo "Graph built successfully!"
    echo "Graph file: $OTP_DATA_DIR/graph.obj"
    echo "Graph size: $(du -h $OTP_DATA_DIR/graph.obj | cut -f1)"
    echo ""
    echo "To start OTP 2.x server:"
    echo "  java -Xmx8g -jar $OTP_JAR --load $OTP_DATA_DIR --serve"
    echo ""
    echo "Or restart containers:"
    echo "  for i in {1..10}; do podman restart howfar-otp\$i; done"
else
    echo "Error: Graph file not created"
    exit 1
fi
