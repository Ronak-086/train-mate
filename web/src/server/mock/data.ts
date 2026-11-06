import type { RouteStop, Station, Train } from "@/lib/api/types";

/**
 * FICTIONAL timetable fixtures. The supplied designs mix stations that don't form a real
 * route (e.g. Nagaon then Lumding, arriving in New Delhi the same afternoon), so these
 * trains exist only to drive the mock. Real data comes from the timetable dataset (spec 8.2).
 */
export const STATIONS: Record<string, Station> = {
  GHY: { code: "GHY", name: "Guwahati" },
  NGC: { code: "NGC", name: "Nagaon" },
  LMG: { code: "LMG", name: "Lumding Jn" },
  NJP: { code: "NJP", name: "New Jalpaiguri" },
  NDLS: { code: "NDLS", name: "New Delhi" },
};

type StopSpec = [code: string, arr: string | null, dep: string | null, dayOffset: number];

const ROUTES: Record<string, StopSpec[]> = {
  "12345": [["GHY", null, "06:30", 0], ["NGC", "08:30", "08:32", 0], ["LMG", "09:10", "09:15", 0], ["NJP", "11:50", "12:05", 0], ["NDLS", "13:50", null, 0]],
  "12346": [["GHY", null, "07:00", 0], ["NJP", "12:30", "12:40", 0], ["NDLS", "18:45", null, 0]],
  "12347": [["LMG", null, "05:00", 0], ["GHY", "08:10", "08:20", 0], ["NDLS", "17:30", null, 0]],
};

const NAMES: Record<string, string> = { "12345": "Express", "12346": "Sample Mail", "12347": "Sample Superfast" };

export function routeFor(trainNo: string): RouteStop[] | null {
  const spec = ROUTES[trainNo];
  if (!spec) return null;
  return spec.map(([code, schedArr, schedDep, dayOffset], i) => ({ seq: i + 1, station: STATIONS[code], schedArr, schedDep, dayOffset }));
}

export function allTrains(): Train[] {
  return Object.keys(ROUTES).map((no) => {
    const stops = routeFor(no)!;
    return { trainNo: no, name: NAMES[no], from: stops[0].station, to: stops[stops.length - 1].station };
  });
}

export const trainName = (no: string) => NAMES[no] ?? no;
