# howfar.nyc

NYC/tri-state interactive isochrone map (transit + bike). Project uses a Clojure/script stack with Leaflet and PostGIS/Postgres. It pre-computes the isochrone data using Open Trip Planner 2.5.

## Quick Start

### Prerequisites

- Java 21+ (required for Clojure, Open Trip Planner 2.5)
- Node.js 18+ (for ClojureScript)
- PostgreSQL with PostGIS extension

If you're computing isochrones:
- Podman (or Docker) with Compose
- Python 3.8+ (for data import scripts)
- it's resource intensive and takes days to compute on a local machine (I created an OTP cluster runs 15 instances at 12GB each)

### 1. Setup Database

The project uses a local PostgreSQL instance

```bash
# Create database and enable PostGIS
createdb howfar
psql -d howfar -c "CREATE EXTENSION IF NOT EXISTS postgis"

```

### 2. Install Dependencies

```bash
npm install
```

### 3. Start Development Servers

```bash
# Terminal 1: Backend
clj -M:run

# Terminal 2: Frontend
npm run dev
```

Backend runs on http://localhost:3001
Frontend runs on http://localhost:8280

## Data

Computing an isochrone map for every intersection in NYC is expensive. The total size of the DB is ~73GB. The computation is expensive given the regional spread and distance that can be covered over 3 hours. To do this effectively, we try to parallelise as much as possible.

### 1. Download GTFS Feeds

```bash
./scripts/import-gtfs.sh
```

Downloads 17 transit feeds:
- MTA Subway, Bus (all boroughs), LIRR, Metro-North
- NJ Transit Rail & Bus
- PATH
- NYC Ferry
- Amtrak
- CT Transit
- SEPTA

GTFS is an open transit specification that allows us to compute transit travel times with Open Trip Planner. There are other route planning libraries that might be more efficient, I haven't researched them thoroughly.

### 2. Download Open Street Maps data

```bash
curl -L -o otp-data/new-york-latest.osm.pbf "https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf"
curl -L -o otp-data/new-jersey-latest.osm.pbf "https://download.geofabrik.de/north-america/us/new-jersey-latest.osm.pbf"
curl -L -o otp-data/connecticut-latest.osm.pbf "https://download.geofabrik.de/north-america/us/connecticut-latest.osm.pbf"
curl -L -o otp-data/pennsylvania-latest.osm.pbf "https://download.geofabrik.de/north-america/us/pennsylvania-latest.osm.pbf"
```

### 3. Build OTP Graph

```bash
./scripts/build-otp-graph.sh
```

This downloads OTP 2.5.0 (if not present) and builds a routing graph from all GTFS feeds and tri-state OSM data. The graph build takes ~12 minutes and produces a ~1.1GB graph file.

Note: OTP 2.5.0 is one of the last OTP versions which supports the particular API endpoints needed for this.

### 4. Import Intersections

```bash
pip install osmium psycopg2-binary
python scripts/import-osm.py otp-data/new-york-latest.osm.pbf
```

### 5. Start OpenTripPlanner Cluster

The project runs 15 OTP instances behind an nginx load balancer for parallel processing (you can edit the compose file accordingly):

```bash
podman compose up -d
podman compose logs -f   # wait for "Grizzly server running"
```

OTP API available at http://localhost:8080/otp/

## Batch Pre-computation

Pre-compute isochrones for all intersections:

```bash
clj -M:batch run transit 12:00:00 weekday 32
```

Parameters:
- **mode**: `transit`, `bike`, or `walk`
- **departure-time**: HH:MM:SS format
- **day-type**: `weekday`, `saturday`, or `sunday`
- **parallelism**: concurrent threads (recommended: 16-32 for 64GB RAM)

Notes:
- If you overload the system, your searches will begin timing out which will lead to truncated isochrone bands. Monitor the logs in the beginning to make sure it's not doing that.
- Monitor CPU/Memory usage to make sure your containers don't get OOMKilled

## API Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /api/health` | Health check |
| `GET /api/intersections/viewport` | Get intersections in map bounds |
| `GET /api/click?lat=X&lng=Y` | Find nearest intersection + compute isochrone |
| `GET /api/isochrone/:id?mode=transit` | Get isochrone for intersection |
| `GET /api/transit/stops/viewport` | Get transit stops in bounds |
| `GET /api/modes` | List available transport modes |
| `GET /api/stats` | Coverage statistics |

## License

MIT
