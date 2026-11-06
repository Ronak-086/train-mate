package app.trainmate.train;

/** JSON shapes for the train endpoints. They match web/src/lib/api/types.ts field for field. */
public final class Dtos {
    private Dtos() {}

    public record StationDto(String code, String name) {}

    public record TrainDto(String trainNo, String name, StationDto from, StationDto to) {}

    public record RouteStopDto(int seq, StationDto station, String schedArr, String schedDep, int dayOffset) {}

    public record EtaDto(StationDto station, String scheduledAt, String etaFrom, String etaTo) {}

    public record TrainStatusDto(
        String trainNo, String date, String source, StationDto lastStation, String lastSeenAt, String fetchedAt,
        Integer delayMinutes, Integer delayLowMin, Integer delayHighMin, String delayLabel,
        EtaDto nextStation, EtaDto destination, boolean stale, String notice) {}
}
