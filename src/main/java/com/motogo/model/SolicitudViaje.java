package com.motogo.model;

public class SolicitudViaje {
    private String idSolicitud;
    private double latitudPasajero;
    private double longitudPasajero;
    private String destino;
    private long timestamp;

    public SolicitudViaje(String idSolicitud, double latitudPasajero, double longitudPasajero, String destino) {
        this.idSolicitud = idSolicitud;
        this.latitudPasajero = latitudPasajero;
        this.longitudPasajero = longitudPasajero;
        this.destino = destino;
        this.timestamp = System.currentTimeMillis();
    }

    public String getIdSolicitud() { return idSolicitud; }
    public double getLatitudPasajero() { return latitudPasajero; }
    public double getLongitudPasajero() { return longitudPasajero; }
    public String getDestino() { return destino; }
    public long getTimestamp() { return timestamp; }
}