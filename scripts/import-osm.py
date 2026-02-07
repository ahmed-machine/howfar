#!/usr/bin/env python3
"""
Import NYC intersections from OpenStreetMap PBF file.

This script extracts intersection nodes from an OSM extract and imports
them into the PostgreSQL database.

Requirements:
    pip install osmium psycopg2-binary

Usage:
    python import-osm.py path/to/new-york-latest.osm.pbf
"""

import sys
import osmium
import psycopg2
from psycopg2.extras import execute_values
import os

# Database connection (defaults match db.sh and config.edn)
DB_CONFIG = {
    'dbname': os.environ.get('DB_NAME', 'howfar'),
    'user': os.environ.get('DB_USER', 'mish'),
    'password': os.environ.get('DB_PASSWORD', ''),
    'host': os.environ.get('DB_HOST', 'localhost'),
    'port': os.environ.get('DB_PORT', '5432')
}

# NYC bounding box (approximate)
NYC_BOUNDS = {
    'min_lat': 40.4774,
    'max_lat': 40.9176,
    'min_lng': -74.2591,
    'max_lng': -73.7004
}

# Regional bounding box (NY, NJ, CT, eastern PA)
TRISTATE_BOUNDS = {
    'min_lat': 39.8,
    'max_lat': 41.5,
    'min_lng': -75.5,
    'max_lng': -73.2
}


class IntersectionHandler(osmium.SimpleHandler):
    """Handler to extract intersection nodes from OSM."""

    def __init__(self, bounds):
        super().__init__()
        self.bounds = bounds
        self.node_way_count = {}  # node_id -> count of ways using it
        self.node_coords = {}     # node_id -> (lat, lng)
        self.intersections = []

    def way(self, w):
        """Count how many ways reference each node."""
        # Only consider highway ways
        if 'highway' not in w.tags:
            return

        highway_type = w.tags.get('highway')
        # Skip non-road types
        if highway_type in ('footway', 'cycleway', 'path', 'steps',
                           'pedestrian', 'service', 'track'):
            return

        for node in w.nodes:
            node_id = node.ref
            if node_id not in self.node_way_count:
                self.node_way_count[node_id] = 0
            self.node_way_count[node_id] += 1

    def node(self, n):
        """Store node coordinates."""
        lat, lng = n.location.lat, n.location.lon

        # Check bounds
        if not (self.bounds['min_lat'] <= lat <= self.bounds['max_lat'] and
                self.bounds['min_lng'] <= lng <= self.bounds['max_lng']):
            return

        self.node_coords[n.id] = (lat, lng)

    def finalize(self):
        """Extract intersections (nodes with 3+ ways)."""
        print(f"Processing {len(self.node_way_count)} nodes...")

        for node_id, count in self.node_way_count.items():
            # Intersection = node where 3+ roads meet
            if count >= 3 and node_id in self.node_coords:
                lat, lng = self.node_coords[node_id]
                self.intersections.append({
                    'osm_node_id': node_id,
                    'lat': lat,
                    'lng': lng
                })

        print(f"Found {len(self.intersections)} intersections")
        return self.intersections


def get_region(lat, lng):
    """Determine region from coordinates (NY, NJ, CT, PA)."""
    # Connecticut: north of ~41.0, east of Hudson
    if lat > 41.0 and lng > -73.73:
        return 'Connecticut'
    # Pennsylvania: west of Delaware River (~-74.7 at Trenton, ~-75.0 at Philly)
    if lng < -74.7:
        return 'Pennsylvania'
    # New Jersey: west of Hudson River / Kill Van Kull
    if lng < -74.05 and lat > 40.5:
        return 'New Jersey'
    if lng < -74.15 and lat <= 40.5:
        return 'New Jersey'
    # Westchester / Hudson Valley (NY state, north of NYC)
    if lat > 41.0:
        return 'New York'
    # NYC boroughs (rough approximation)
    if lat > 40.8:
        return 'Bronx'
    if lng > -73.85 and lat < 40.75:
        return 'Queens'
    if lat < 40.65:
        return 'Brooklyn'
    if lng < -74.05:
        return 'Staten Island'
    return 'Manhattan'


def import_to_database(intersections):
    """Import intersections to PostgreSQL."""
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    print(f"Importing {len(intersections)} intersections to database...")

    # Prepare data with borough
    data = []
    for i in intersections:
        borough = get_region(i['lat'], i['lng'])
        data.append((
            i['osm_node_id'],
            None,  # name (could be enriched later)
            i['lat'],
            i['lng'],
            f"SRID=4326;POINT({i['lng']} {i['lat']})",
            borough
        ))

    # Batch insert (sample_group is auto-generated)
    insert_query = """
        INSERT INTO intersections (osm_node_id, name, lat, lng, geom, borough)
        VALUES %s
        ON CONFLICT (osm_node_id) DO UPDATE SET
            lat = EXCLUDED.lat,
            lng = EXCLUDED.lng,
            geom = EXCLUDED.geom,
            borough = EXCLUDED.borough
    """

    execute_values(cur, insert_query, data, page_size=1000)
    conn.commit()

    # Verify count
    cur.execute("SELECT COUNT(*) FROM intersections")
    count = cur.fetchone()[0]
    print(f"Database now has {count} intersections")

    cur.close()
    conn.close()


def main():
    if len(sys.argv) < 2:
        print("Usage: python import-osm.py <osm-pbf-file>")
        print("\nDownload NYC extract from:")
        print("  https://download.geofabrik.de/north-america/us/new-york.html")
        sys.exit(1)

    pbf_file = sys.argv[1]
    use_tristate = '--tristate' in sys.argv

    bounds = TRISTATE_BOUNDS if use_tristate else NYC_BOUNDS
    print(f"Using bounds: {bounds}")

    # First pass: collect way references
    print(f"Processing {pbf_file}...")
    handler = IntersectionHandler(bounds)

    # Process ways first
    print("Pass 1: Counting way references...")
    handler.apply_file(pbf_file, locations=True, idx='flex_mem')

    # Finalize to get intersections
    intersections = handler.finalize()

    # Import to database
    if intersections:
        import_to_database(intersections)
        print("Import complete!")
    else:
        print("No intersections found. Check the OSM file and bounds.")


if __name__ == '__main__':
    main()
