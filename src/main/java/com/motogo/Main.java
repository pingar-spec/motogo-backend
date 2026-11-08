package com.motogo;

import com.motogo.model.Conductor;
import com.motogo.service.GeolocalizacionService;
import io.javalin.Javalin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Main {
    private static List<Conductor> listaConductores = new ArrayList<>();
    private static Map<String, ViajeEnSubasta> viajesActivos = new HashMap<>();

    public static void main(String[] args) {
        inicializarConductoresHuaral();

        int port = getPort();

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.anyHost();
                });
            });
        }).start(port);

        app.get("/", ctx -> {
            ctx.result("¡Servidor MotoGo Backend en Huaral funcionando correctamente!");
        });

        // 1. Endpoint para que el pasajero solicite viaje (Despacho por Proximidad)
        app.get("/api/solicitar-viaje", ctx -> {
            Double latPasajero = ctx.queryParamAsClass("lat", Double.class).get();
            Double lonPasajero = ctx.queryParamAsClass("lon", Double.class).get();
            String destino = ctx.queryParam("destino");

            if (latPasajero == null || lonPasajero == null) {
                ctx.status(400).json("{\"error\": \"Se requieren la latitud y longitud del pasajero\"}");
                return;
            }

            System.out.println("Solicitud de viaje hacia: " + (destino != null ? destino : "Destino no especificado"));

            Conductor mejorConductor = null;
            double menorDistancia = Double.MAX_VALUE;

            for (Conductor c : listaConductores) {
                if (c.isDisponible() && c.isSuscripcionActiva()) {
                    double distancia = GeolocalizacionService.calcularDistanciaMetros(
                        latPasajero, lonPasajero, c.getLatitud(), c.getLongitud()
                    );

                    if (distancia < menorDistancia) {
                        menorDistancia = distancia;
                        mejorConductor = c;
                    }
                }
            }

            if (mejorConductor != null) {
                String idSolicitud = UUID.randomUUID().toString().substring(0, 8);
                long tiempoExpiracion = System.currentTimeMillis() + 20000; // 20 segundos de subasta

                ViajeEnSubasta viaje = new ViajeEnSubasta(mejorConductor.getId(), tiempoExpiracion);
                viajesActivos.put(idSolicitud, viaje);

                ctx.status(200).json(String.format(
                    "{\"status\": \"SUBASTA_INICIADA\", \"id_solicitud\": \"%s\", \"conductor_asignado\": \"%s\", \"placa\": \"%s\", \"distancia_metros\": %.2f, \"tiempo_limite_segundos\": 20}",
                    idSolicitud, mejorConductor.getNombre(), mejorConductor.getPlaca(), menorDistancia
                ));
            } else {
                ctx.status(404).json("{\"status\": \"SIN_CONDUCTORES\", \"mensaje\": \"No hay mototaxistas disponibles cerca en este momento\"}");
            }
        });

        // 2. Endpoint para que el conductor acepte la carrera dentro de los 20 segundos
        app.post("/api/aceptar-viaje", ctx -> {
            String idSolicitud = ctx.queryParam("id_solicitud");
            String conductorId = ctx.queryParam("conductor_id");

            ViajeEnSubasta viaje = viajesActivos.get(idSolicitud);

            if (viaje == null) {
                ctx.status(404).json("{\"error\": \"La solicitud de viaje no existe o ya expiró.\"}");
                return;
            }

            long tiempoActual = System.currentTimeMillis();
            if (tiempoActual > viaje.getTiempoExpiracion()) {
                ctx.status(400).json("{\"error\": \"El tiempo de subasta de 20 segundos ha expirado.\"}");
                viajesActivos.remove(idSolicitud);
                return;
            }

            if (viaje.getConductorId().equals(conductorId)) {
                ctx.status(200).json("{\"status\": \"VIAJE_ACEPTADO\", \"mensaje\": \"¡Carrera asignada con éxito! Dirígete al punto del pasajero.\"}");
                viajesActivos.remove(idSolicitud);
            } else {
                ctx.status(403).json("{\"error\": \"Este viaje no fue asignado a este conductor.\"}");
            }
        });

        System.out.println("==================================================");
        System.out.println(" Servidor MotoGo Huaral iniciado en el puerto: " + port);
        System.out.println("==================================================");
    }

    private static void inicializarConductoresHuaral() {
        listaConductores.add(new Conductor("1", "Tío de Piero", "ABC-123", -11.5055, -77.2111, true, true));
        listaConductores.add(new Conductor("2", "Carlos Pérez", "XYZ-789", -11.5080, -77.2150, true, true));
    }

    private static int getPort() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                return Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                System.out.println("Puerto inválido, usando 7070 por defecto.");
            }
        }
        return 7070;
    }

    private static class ViajeEnSubasta {
        private String conductorId;
        private long tiempoExpiracion;

        public ViajeEnSubasta(String conductorId, long tiempoExpiracion) {
            this.conductorId = conductorId;
            this.tiempoExpiracion = tiempoExpiracion;
        }

        public String getConductorId() { return conductorId; }
        public long getTiempoExpiracion() { return tiempoExpiracion; }
    }
}