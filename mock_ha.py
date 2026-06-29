#!/usr/bin/env python3
"""
Mock Home Assistant REST API server for demo/offline use.
Handles all endpoints the learnmcp server calls.
Run: python3 mock_ha.py [port]  (default 8123)
"""

import json, sys, re
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime, timezone, timedelta
from urllib.parse import urlparse, parse_qs
import random

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8123

NOW = datetime.now(timezone.utc)

ENTITIES = [
    {
        "entity_id": "sensor.outdoor_temperature",
        "state": "22.4",
        "attributes": {
            "friendly_name": "Outdoor Temperature",
            "unit_of_measurement": "°C",
            "device_class": "temperature",
        },
        "last_changed": (NOW - timedelta(minutes=5)).isoformat(),
        "last_updated": (NOW - timedelta(minutes=2)).isoformat(),
    },
    {
        "entity_id": "sensor.living_room_humidity",
        "state": "58",
        "attributes": {
            "friendly_name": "Living Room Humidity",
            "unit_of_measurement": "%",
            "device_class": "humidity",
        },
        "last_changed": (NOW - timedelta(minutes=10)).isoformat(),
        "last_updated": (NOW - timedelta(minutes=3)).isoformat(),
    },
    {
        "entity_id": "sensor.living_room_temperature",
        "state": "21.8",
        "attributes": {
            "friendly_name": "Living Room Temperature",
            "unit_of_measurement": "°C",
            "device_class": "temperature",
        },
        "last_changed": (NOW - timedelta(minutes=7)).isoformat(),
        "last_updated": (NOW - timedelta(minutes=1)).isoformat(),
    },
    {
        "entity_id": "switch.living_room_fan",
        "state": "off",
        "attributes": {
            "friendly_name": "Living Room Fan",
        },
        "last_changed": (NOW - timedelta(hours=2)).isoformat(),
        "last_updated": (NOW - timedelta(hours=2)).isoformat(),
    },
    {
        "entity_id": "light.bedroom",
        "state": "on",
        "attributes": {
            "friendly_name": "Bedroom Light",
            "brightness": 180,
            "color_temp": 4000,
        },
        "last_changed": (NOW - timedelta(minutes=30)).isoformat(),
        "last_updated": (NOW - timedelta(minutes=30)).isoformat(),
    },
    {
        "entity_id": "sensor.power_consumption",
        "state": "342",
        "attributes": {
            "friendly_name": "Power Consumption",
            "unit_of_measurement": "W",
            "device_class": "power",
        },
        "last_changed": (NOW - timedelta(seconds=30)).isoformat(),
        "last_updated": (NOW - timedelta(seconds=30)).isoformat(),
    },
]

ENTITY_MAP = {e["entity_id"]: e for e in ENTITIES}

def make_history(entity_id, start, end):
    """Generate plausible sensor history between start and end."""
    entity = ENTITY_MAP.get(entity_id)
    if not entity:
        return []
    base = float(entity["state"]) if entity["state"].replace(".", "").isdigit() else 0
    result = []
    t = start
    while t < end:
        val = round(base + random.uniform(-2, 2), 1)
        row = dict(entity)
        row = dict(entity)
        row = {**entity, "state": str(val), "last_changed": t.isoformat(), "last_updated": t.isoformat()}
        result.append(row)
        t += timedelta(minutes=5)
    return result


class HAHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[mock-ha] {fmt % args}", flush=True)

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = parse_qs(parsed.query)

        if path == "/api" or path == "/api/":
            self.send_json({"message": "API running."})

        elif path == "/api/states":
            self.send_json(ENTITIES)

        elif path.startswith("/api/states/"):
            eid = path[len("/api/states/"):]
            if eid in ENTITY_MAP:
                self.send_json(ENTITY_MAP[eid])
            else:
                self.send_json({"message": "Entity not found"}, 404)

        elif path.startswith("/api/history/period/"):
            # path: /api/history/period/<start_iso>
            # qs: filter_entity_id, end_time
            entity_ids_raw = qs.get("filter_entity_id", [""])[0]
            end_raw = qs.get("end_time", [NOW.isoformat()])[0]
            entity_ids = [e.strip() for e in entity_ids_raw.split(",") if e.strip()]
            try:
                start_dt = datetime.fromisoformat(path.split("/api/history/period/")[1])
                end_dt = datetime.fromisoformat(end_raw)
            except Exception:
                start_dt = NOW - timedelta(hours=1)
                end_dt = NOW
            result = [make_history(eid, start_dt, end_dt) for eid in entity_ids]
            self.send_json(result)

        else:
            self.send_json({"message": "Not found"}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length else {}

        if path.startswith("/api/services/"):
            parts = path[len("/api/services/"):].split("/")
            if len(parts) >= 2:
                domain, service = parts[0], parts[1]
                eid = body.get("entity_id", "")
                print(f"[mock-ha] service call: {domain}.{service} on {eid}", flush=True)
                if eid in ENTITY_MAP:
                    if service in ("turn_on", "toggle"):
                        ENTITY_MAP[eid]["state"] = "on"
                    elif service == "turn_off":
                        ENTITY_MAP[eid]["state"] = "off"
                    self.send_json([ENTITY_MAP[eid]])
                else:
                    self.send_json([])
            else:
                self.send_json({"message": "Bad service path"}, 400)
        else:
            self.send_json({"message": "Not found"}, 404)


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), HAHandler)
    print(f"Mock HA running on http://localhost:{PORT}", flush=True)
    server.serve_forever()
