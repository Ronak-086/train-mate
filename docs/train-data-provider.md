# Live Train Data: RailRadar Integration

## Active Provider: RailRadar
TrainMate connects to **RailRadar** for live Indian Railways train status tracking.

- **Base URL:** `https://api.railradar.in/v1/trains/{trainNo}/live`
- **Authentication:** `Authorization: Bearer <RAILRADAR_API_KEY>`
- **Active Plan:** Free / Hobby tier (10 requests/min, 100 requests quota).
- **Default Budget Guard:** `PROVIDER_DAILY_BUDGET=100` calls/day.

### Live Verified Response Example
```json
{
  "success": true,
  "data": {
    "trainNumber": "12919",
    "trainName": "Malwa SF Express",
    "status": "running",
    "isLive": true,
    "delayMinutes": 33,
    "currentLocation": {
      "stationCode": "SANK",
      "stationName": "Sank",
      "delayMinutes": 33
    },
    "previousHalt": { "stationCode": "GWL", "stationName": "Gwalior Jn" },
    "nextHalt": { "stationCode": "MRA", "stationName": "Morena" },
    "lastUpdatedAt": "2026-11-04T23:50:41+05:30"
  }
}
```

### Safety & Resilience
- **Circuit Breaker:** Opens automatically after 5 consecutive failures for 60 seconds.
- **Budget Guard:** Rejects calls once daily quota is reached to prevent overages.
- **Fallback:** When unconfigured, unavailable, or off-route, cleanly falls back to timetable snapshots with the honest badge `"Timetable only. Live position unavailable."`.
