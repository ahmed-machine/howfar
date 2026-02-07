#!/usr/bin/env python3
"""
Extract subway & rail route geometries from GTFS feeds into a single GeoJSON file.

Usage:
    python scripts/generate-transit-lines.py otp-data/feeds

Output:
    public/data/transit-lines.geojson
"""

import csv
import io
import json
import math
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

# Feeds to process: (zip_stem, agency_label, type_label)
FEEDS = [
    ("mta-subway",      "MTA",          "subway"),
    ("path",            "PATH",         "subway"),
    ("lirr",            "LIRR",         "rail"),
    ("metro-north",     "Metro-North",  "rail"),
    ("nj-transit-rail", "NJ Transit",   "rail"),
    ("amtrak",          "Amtrak",       "rail"),
    ("septa-rail",      "SEPTA",        "rail"),
    ("ct-transit",      "CT Transit",   "rail"),
]

# GTFS route_types to include (0=tram/light rail, 1=subway, 2=rail)
RAIL_TYPES = {0, 1, 2}

# Douglas-Peucker simplification tolerance (~8m at mid-latitudes)
EPSILON = 0.0001
# More aggressive simplification for long-distance rail (~80m)
EPSILON_LONG_DISTANCE = 0.001

OUTPUT_PATH = Path("public/data/transit-lines.geojson")


def find_feed(feeds_dir: Path, stem: str) -> Path | None:
    """Find a feed zip, trying both {stem}.gtfs.zip and {stem}.zip."""
    for suffix in (".gtfs.zip", ".zip"):
        p = feeds_dir / f"{stem}{suffix}"
        if p.exists():
            return p
    return None


def read_csv(zf: zipfile.ZipFile, filename: str) -> list[dict]:
    """Read a CSV file from inside a zip, return list of dicts."""
    try:
        with zf.open(filename) as f:
            text = io.TextIOWrapper(f, encoding="utf-8-sig")
            return list(csv.DictReader(text))
    except KeyError:
        return []


def perpendicular_distance(point, line_start, line_end):
    """Distance from point to line segment (lat/lng as floats)."""
    dx = line_end[0] - line_start[0]
    dy = line_end[1] - line_start[1]
    if dx == 0 and dy == 0:
        return math.hypot(point[0] - line_start[0], point[1] - line_start[1])
    t = max(0, min(1, ((point[0] - line_start[0]) * dx + (point[1] - line_start[1]) * dy) / (dx * dx + dy * dy)))
    proj = (line_start[0] + t * dx, line_start[1] + t * dy)
    return math.hypot(point[0] - proj[0], point[1] - proj[1])


def douglas_peucker(coords, epsilon):
    """Simplify a polyline using Douglas-Peucker algorithm."""
    if len(coords) <= 2:
        return coords
    max_dist = 0
    max_idx = 0
    for i in range(1, len(coords) - 1):
        d = perpendicular_distance(coords[i], coords[0], coords[-1])
        if d > max_dist:
            max_dist = d
            max_idx = i
    if max_dist > epsilon:
        left = douglas_peucker(coords[:max_idx + 1], epsilon)
        right = douglas_peucker(coords[max_idx:], epsilon)
        return left[:-1] + right
    else:
        return [coords[0], coords[-1]]


def process_feed(feeds_dir: Path, stem: str, agency: str, type_label: str) -> list[dict]:
    """Process a single GTFS feed and return GeoJSON features."""
    feed_path = find_feed(feeds_dir, stem)
    if not feed_path:
        print(f"  [SKIP] {stem}: feed not found")
        return []

    zf = zipfile.ZipFile(feed_path)
    features = []

    # 1. Parse routes (filter to rail types)
    routes_raw = read_csv(zf, "routes.txt")
    routes = {}
    for r in routes_raw:
        try:
            rt = int(r.get("route_type", -1))
        except (ValueError, TypeError):
            continue
        if rt not in RAIL_TYPES:
            continue
        routes[r["route_id"]] = {
            "short_name": r.get("route_short_name", "").strip(),
            "long_name": r.get("route_long_name", "").strip(),
            "color": r.get("route_color", "888888").strip(),
            "type": rt,
        }

    if not routes:
        print(f"  [SKIP] {stem}: no rail/subway routes found")
        zf.close()
        return []

    # 2. Parse trips → (route_id, direction_id) → set of shape_ids
    trips_raw = read_csv(zf, "trips.txt")
    route_dir_shapes = defaultdict(set)  # (route_id, dir) → {shape_id}
    route_dir_trips = defaultdict(list)  # (route_id, dir) → [trip_id] (for shapeless feeds)
    has_shapes = False
    for t in trips_raw:
        rid = t.get("route_id", "")
        if rid not in routes:
            continue
        did = t.get("direction_id", "0") or "0"
        sid = t.get("shape_id", "").strip()
        if sid:
            route_dir_shapes[(rid, did)].add(sid)
            has_shapes = True
        route_dir_trips[(rid, did)].append(t.get("trip_id", ""))

    # 3. Parse shapes (if available)
    shapes = defaultdict(list)  # shape_id → [(seq, lat, lng)]
    if has_shapes:
        for row in read_csv(zf, "shapes.txt"):
            sid = row.get("shape_id", "").strip()
            if not sid:
                continue
            try:
                seq = int(row.get("shape_pt_sequence", 0))
                lat = float(row["shape_pt_lat"])
                lng = float(row["shape_pt_lon"])
                shapes[sid].append((seq, lat, lng))
            except (ValueError, KeyError):
                continue
        # Sort each shape by sequence
        for sid in shapes:
            shapes[sid].sort(key=lambda x: x[0])

    # 4. If no shapes, synthesize from stop_times + stops (e.g. PATH)
    stops = {}
    if not has_shapes:
        for row in read_csv(zf, "stops.txt"):
            try:
                stops[row["stop_id"]] = (float(row["stop_lat"]), float(row["stop_lon"]))
            except (ValueError, KeyError):
                continue
        # Build trip_id → ordered stop coords
        stop_times_raw = read_csv(zf, "stop_times.txt")
        trip_stops = defaultdict(list)
        for st in stop_times_raw:
            tid = st.get("trip_id", "")
            try:
                seq = int(st.get("stop_sequence", 0))
                stop_id = st["stop_id"]
                if stop_id in stops:
                    trip_stops[tid].append((seq, stops[stop_id]))
            except (ValueError, KeyError):
                continue
        for tid in trip_stops:
            trip_stops[tid].sort(key=lambda x: x[0])

    # 5. For each (route, direction), pick best shape and emit feature
    for (rid, did), shape_ids in route_dir_shapes.items():
        if rid not in routes:
            continue
        # Pick shape with most points
        best_sid = max(shape_ids, key=lambda s: len(shapes.get(s, [])))
        pts = shapes.get(best_sid, [])
        if len(pts) < 2:
            continue
        coords = [(lng, lat) for (_, lat, lng) in pts]
        eps = EPSILON_LONG_DISTANCE if stem == "amtrak" else EPSILON
        coords = douglas_peucker(coords, eps)
        if len(coords) < 2:
            continue
        rinfo = routes[rid]
        color = rinfo["color"].lstrip("#")
        features.append(make_feature(coords, rinfo, rid, agency, type_label, color))

    # For shapeless routes, synthesize from stop sequences
    if not has_shapes:
        for (rid, did), trip_ids in route_dir_trips.items():
            if rid not in routes:
                continue
            # Pick the trip with the most stops
            best_tid = max(trip_ids, key=lambda t: len(trip_stops.get(t, [])))
            pts = trip_stops.get(best_tid, [])
            if len(pts) < 2:
                continue
            coords = [(coord[1], coord[0]) for (_, coord) in pts]  # (lng, lat)
            rinfo = routes[rid]
            color = rinfo["color"].lstrip("#")
            features.append(make_feature(coords, rinfo, rid, agency, type_label, color))

    zf.close()
    print(f"  [OK] {stem}: {len(features)} line(s)")
    return features


def round_coords(coords, precision=4):
    """Round coordinate pairs to given decimal places."""
    return [[round(lng, precision), round(lat, precision)] for lng, lat in coords]


def make_feature(coords, rinfo, route_id, agency, type_label, color):
    """Create a GeoJSON Feature dict."""
    name = rinfo["short_name"] or rinfo["long_name"] or route_id
    coords = round_coords(coords)
    return {
        "type": "Feature",
        "properties": {
            "route_id": route_id,
            "route_short_name": name,
            "route_color": f"#{color}" if color else "#888888",
            "agency": agency,
            "type": type_label,
        },
        "geometry": {
            "type": "LineString",
            "coordinates": coords,
        },
    }


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <feeds_directory>")
        sys.exit(1)

    feeds_dir = Path(sys.argv[1])
    if not feeds_dir.is_dir():
        print(f"Error: {feeds_dir} is not a directory")
        sys.exit(1)

    print(f"Processing feeds from {feeds_dir}...")
    all_features = []
    for stem, agency, type_label in FEEDS:
        features = process_feed(feeds_dir, stem, agency, type_label)
        all_features.extend(features)

    geojson = {
        "type": "FeatureCollection",
        "features": all_features,
    }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump(geojson, f, separators=(",", ":"))

    size_kb = OUTPUT_PATH.stat().st_size / 1024
    print(f"\nWrote {len(all_features)} features to {OUTPUT_PATH} ({size_kb:.0f} KB)")


if __name__ == "__main__":
    main()
