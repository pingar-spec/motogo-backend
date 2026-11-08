package com.motogo.model;

public class Conductor {
    private String id;
    private String nombre;
    private String placa;
    private double latitud;
    private double longitud;
    private boolean disponible;
    private boolean suscripcionActiva;

    public Conductor(String id, String nombre, String placa, double latitud, double longitud, boolean disponible, boolean suscripcionActiva) {
        this.id = id;
        this.nombre = nombre;
        this.placa = placa;
        this.latitud = latitud;
        this.longitud = longitud;
        this.disponible = disponible;
        this.suscripcionActiva = suscripcionActiva;
    }

    public String getId() { return id; }
    public String getNombre() { return nombre; }
    public String getPlaca() { return placa; }
    public double getLatitud() { return latitud; }
    public double getLongitud() { return longitud; }
    public boolean isDisponible() { return disponible; }
    public boolean isSuscripcionActiva() { return suscripcionActiva; }
}