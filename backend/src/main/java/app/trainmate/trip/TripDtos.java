package app.trainmate.trip;

import app.trainmate.train.Dtos.StationDto;

public final class TripDtos {
    private TripDtos() {}

    public record TripDto(String id, String trainNo, String trainName, String journeyDate, StationDto boardingStn, StationDto destStn,
                          String travelClass, String coach, Integer seatNo, String berthType, String createdAt) {}
}
