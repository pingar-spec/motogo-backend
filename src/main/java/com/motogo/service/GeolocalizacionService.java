package com.motogo.service;

public class GeolocalizacionService {
    // Radio medio de la Tierra en kilómetros
    private static final double RADIO_TIERRA_KM = 6371.0;

    /**
     * Calcula la distancia en metros entre dos puntos geográficos (latitud y longitud).
     * Ideal para encontrar al mototaxista más cercano a la solicitud del pasajero.
     */
    public static double calcularDistanciaMetros(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double distanciaKm = RADIO_TIERRA_KM * c;
        
        // Retornamos la distancia convertida en metros
        return distanciaKm * 1000.0;
    }
}