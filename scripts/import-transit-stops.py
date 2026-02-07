#!/usr/bin/env python3
"""
Import transit stops from GTFS feeds into PostgreSQL.

This script reads stops.txt from each GTFS feed and imports
them into the transit_stops table.

Requirements:
    pip install psycopg2-binary

Usage:
    python import-transit-stops.py path/to/gtfs-directory
"""

import sys
import os
import csv
import zipfile
import psycopg2
from psycopg2.extras import execute_values

# Load environment file if it exists
env_file = '/opt/howfar/config/howfar.env'
if os.path.isfile(env_file):
    with open(env_file) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, _, value = line.partition('=')
                os.environ.setdefault(key.strip(), value.strip())

# Database connection
DB_CONFIG = {
    'dbname': os.environ.get('DB_NAME', 'howfar'),
    'user': os.environ.get('DB_USER', 'howfar'),
    'password': os.environ.get('DB_PASSWORD', 'howfar'),
    'host': os.environ.get('DB_HOST', 'localhost'),
    'port': os.environ.get('DB_PORT', '5432')
}

# Map GTFS file names to agency names
AGENCY_MAP = {
    'mta-subway': ('MTA', 'subway'),
    'mta-bus-bronx': ('MTA', 'bus'),
    'mta-bus-brooklyn': ('MTA', 'bus'),
    'mta-bus-manhattan': ('MTA', 'bus'),
    'mta-bus-queens': ('MTA', 'bus'),
    'mta-bus-staten-island': ('MTA', 'bus'),
    'mta-bus-company': ('MTA', 'bus'),
    'lirr': ('LIRR', 'rail'),
    'metro-north': ('Metro-North', 'rail'),
    'nj-transit': ('NJ Transit', 'rail'),
    'nj-transit-rail': ('NJ Transit', 'rail'),
    'nj-transit-bus': ('NJ Transit', 'bus'),
    'path': ('PATH', 'subway'),
    'nyc-ferry': ('NYC Ferry', 'ferry'),
    'amtrak': ('Amtrak', 'rail'),
    'ct-transit': ('CT Transit', 'bus'),
    'septa-bus': ('SEPTA', 'bus'),
    'septa-rail': ('SEPTA', 'rail'),
}


def read_stops_from_gtfs(gtfs_path, agency_name, stop_type):
    """Read stops.txt from a GTFS zip file."""
    stops = []

    try:
        with zipfile.ZipFile(gtfs_path, 'r') as zf:
            with zf.open('stops.txt') as f:
                # Decode bytes to string
                reader = csv.DictReader(line.decode('utf-8') for line in f)

                for row in reader:
                    try:
                        lat = float(row.get('stop_lat', 0))
                        lng = float(row.get('stop_lon', 0))

                        if lat == 0 or lng == 0:
                            continue

                        stops.append({
                            'gtfs_stop_id': row.get('stop_id', ''),
                            'stop_name': row.get('stop_name', 'Unknown'),
                            'lat': lat,
                            'lng': lng,
                            'stop_type': stop_type,
                            'agency': agency_name
                        })
                    except (ValueError, KeyError) as e:
                        continue

    except Exception as e:
        print(f"  Error reading {gtfs_path}: {e}")

    return stops


def import_to_database(stops):
    """Import stops to PostgreSQL."""
    if not stops:
        return 0

    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    # Prepare data
    data = []
    for s in stops:
        # Create unique ID by combining agency and stop_id
        unique_id = f"{s['agency']}_{s['gtfs_stop_id']}"
        data.append((
            unique_id,
            s['stop_name'],
            s['lat'],
            s['lng'],
            f"SRID=4326;POINT({s['lng']} {s['lat']})",
            s['stop_type'],
            s['agency']
        ))

    # Batch insert
    insert_query = """
        INSERT INTO transit_stops (gtfs_stop_id, stop_name, lat, lng, geom, stop_type, agency)
        VALUES %s
        ON CONFLICT (gtfs_stop_id) DO UPDATE SET
            stop_name = EXCLUDED.stop_name,
            lat = EXCLUDED.lat,
            lng = EXCLUDED.lng,
            geom = EXCLUDED.geom,
            stop_type = EXCLUDED.stop_type,
            agency = EXCLUDED.agency
    """

    execute_values(cur, insert_query, data, page_size=1000)
    conn.commit()

    cur.close()
    conn.close()

    return len(data)


def main():
    if len(sys.argv) < 2:
        print("Usage: python import-transit-stops.py <gtfs-directory>")
        sys.exit(1)

    gtfs_dir = sys.argv[1]

    if not os.path.isdir(gtfs_dir):
        print(f"Error: {gtfs_dir} is not a directory")
        sys.exit(1)

    total_stops = 0

    # Process each GTFS file
    for filename in os.listdir(gtfs_dir):
        if not filename.endswith('.zip'):
            continue

        base_name = filename.removesuffix('.gtfs.zip').removesuffix('.zip')
        gtfs_path = os.path.join(gtfs_dir, filename)

        # Get agency info
        agency_info = AGENCY_MAP.get(base_name, ('Unknown', 'other'))
        agency_name, stop_type = agency_info

        print(f"Processing {filename} ({agency_name}, {stop_type})...")

        stops = read_stops_from_gtfs(gtfs_path, agency_name, stop_type)
        if stops:
            count = import_to_database(stops)
            print(f"  Imported {count} stops")
            total_stops += count
        else:
            print(f"  No stops found")

    # Print summary
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM transit_stops")
    db_count = cur.fetchone()[0]
    cur.close()
    conn.close()

    print(f"\nImport complete!")
    print(f"Total stops imported this run: {total_stops}")
    print(f"Total stops in database: {db_count}")


if __name__ == '__main__':
    main()
