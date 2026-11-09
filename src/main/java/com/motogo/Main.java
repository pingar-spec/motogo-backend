package com.motogo;

import io.javalin.Javalin;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class Main {

    private static final String HOST = "gateway01.sa-east-1.prod.aws.tidbcloud.com";
    private static final String PORT_DB = "4000";
    private static final String USER = "VqAp2wi3ep6JvL1.root";
    private static final String PASSWORD = "wYgdmBp2KH2hG3FM";

    public static void main(String[] args) {
        inicializarBaseDeDatosNacional();

        int puertoApp = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            puertoApp = Integer.parseInt(portEnv);
        }

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
        }).start(puertoApp);

        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "*");
        });

        System.out.println("¡Servidor MotoGo Backend Activo en el puerto " + puertoApp + "!");

        // 1. PASAJERO: Solicitar Viaje y Asignar Conductor
        app.post("/api/solicitar-viaje", ctx -> {
            String latStr = ctx.queryParam("lat");
            String lonStr = ctx.queryParam("lon");
            String destino = ctx.queryParam("destino");

            if (latStr == null || lonStr == null) {
                ctx.status(400).json(Map.of("exito", false, "mensaje", "Faltan coordenadas GPS del pasajero."));
                return;
            }

            double pasajeroLat = Double.parseDouble(latStr);
            double pasajeroLon = Double.parseDouble(lonStr);

            registrarSolicitudDemanda(pasajeroLat, pasajeroLon, destino);
            Map<String, Object> conductorAsignado = buscarConductorMasCercano(pasajeroLat, pasajeroLon);

            if (conductorAsignado != null) {
                int conductorId = (int) conductorAsignado.get("id");
                String nombreConductor = (String) conductorAsignado.get("nombre");
                String placaConductor = (String) conductorAsignado.get("placa");
                double distancia = (double) conductorAsignado.get("distancia");

                int viajeId = crearViajePendiente(conductorId, pasajeroLat, pasajeroLon, destino);

                System.out.println("✅ ¡Viaje enlazado! Conductor: " + nombreConductor + " (" + placaConductor + ")");
                ctx.json(Map.of(
                    "exito", true,
                    "viaje_id", viajeId,
                    "conductor_asignado", nombreConductor,
                    "placa", placaConductor,
                    "distancia_km", distancia,
                    "destino", destino != null ? destino : "Destino general"
                ));
            } else {
                System.out.println("⚠️ Solicitud recibida, pero NO hay conductores registrados en la BD.");
                ctx.status(404).json(Map.of("exito", false, "mensaje", "No hay unidades disponibles en este momento. Conecta el radar en tu celular."));
            }
        });

        // 2. CONDUCTOR: Registrar / Transmitir GPS
        app.post("/api/registrar-conductor", ctx -> {
            String nombre = ctx.formParam("nombre");
            String placa = ctx.formParam("placa");
            String latStr = ctx.formParam("lat");
            String lonStr = ctx.formParam("lon");

            if (nombre == null || placa == null || latStr == null || lonStr == null) {
                ctx.status(400).json(Map.of("exito", false, "mensaje", "Faltan datos del conductor."));
                return;
            }

            String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
            String query = "INSERT INTO conductores (nombre, placa, ciudad_id, latitud, longitud, disponible) VALUES (?, ?, 2, ?, ?, TRUE) " +
                           "ON DUPLICATE KEY UPDATE latitud = VALUES(latitud), longitud = VALUES(longitud), disponible = TRUE";

            try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
                 PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, nombre);
                pstmt.setString(2, placa);
                pstmt.setDouble(3, Double.parseDouble(latStr));
                pstmt.setDouble(4, Double.parseDouble(lonStr));
                pstmt.executeUpdate();
                ctx.json(Map.of("exito", true));
            } catch (Exception e) {
                System.err.println("❌ Error al guardar conductor: " + e.getMessage());
                ctx.status(500).json(Map.of("exito", false, "error", e.getMessage()));
            }
        });

        // 3. CONDUCTOR: Verificar si tiene carreras pendientes
        app.get("/api/verificar-viaje-conductor", ctx -> {
            String placa = ctx.queryParam("placa");
            if (placa == null) {
                ctx.status(400).json(Map.of("en_espera", false));
                return;
            }

            String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
            String query = "SELECT v.id, v.latitud, v.longitud, v.destino, 'Piero Pasajero' as pasajero FROM viajes v " +
                           "JOIN conductores c ON v.conductor_id = c.id " +
                           "WHERE c.placa = ? AND v.estado = 'PENDIENTE' " +
                           "AND TIMESTAMPDIFF(SECOND, v.creado_en, NOW()) <= 45 LIMIT 1";

            try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
                 PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, placa);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        ctx.json(Map.of(
                            "en_espera", true,
                            "viaje_id", rs.getInt("id"),
                            "latPasajero", rs.getDouble("latitud"),
                            "lonPasajero", rs.getDouble("longitud"),
                            "destino", rs.getString("destino"),
                            "pasajero", rs.getString("pasajero")
                        ));
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error al verificar viaje conductor: " + e.getMessage());
            }

            ctx.json(Map.of("en_espera", false));
        });

        // 4. CONDUCTOR: Aceptar o Rechazar la carrera
        app.post("/api/responder-viaje", ctx -> {
            String viajeId = ctx.formParam("viaje_id");
            String respuesta = ctx.formParam("respuesta");

            if (viajeId == null || respuesta == null) {
                ctx.status(400).json(Map.of("exito", false, "mensaje", "Datos incompletos."));
                return;
            }

            String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
            String query = "UPDATE viajes SET estado = ? WHERE id = ?";

            try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
                 PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, respuesta.toUpperCase());
                pstmt.setInt(2, Integer.parseInt(viajeId));
                pstmt.executeUpdate();
                ctx.json(Map.of("exito", true, "estado", respuesta.toUpperCase()));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("exito", false, "error", e.getMessage()));
            }
        });

        // 5. PASAJERO: Consultar el estado del viaje en tiempo real (Aceptado/Rechazado)
        app.get("/api/verificar-estado-viaje", ctx -> {
            String viajeIdStr = ctx.queryParam("viaje_id");
            if (viajeIdStr == null) {
                ctx.status(400).json(Map.of("estado", "DESCONOCIDO"));
                return;
            }

            String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
            String query = "SELECT estado FROM viajes WHERE id = ?";

            try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
                 PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setInt(1, Integer.parseInt(viajeIdStr));
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        ctx.json(Map.of("estado", rs.getString("estado")));
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error verificando estado del viaje: " + e.getMessage());
            }

            ctx.json(Map.of("estado", "PENDIENTE"));
        });

        // 6. DIAGNÓSTICO: Listar conductores
        app.get("/api/listar-conductores", ctx -> {
            String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
            StringBuilder json = new StringBuilder("[");
            String query = "SELECT id, nombre, placa, latitud, longitud, disponible FROM conductores";

            try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {

                boolean primero = true;
                while (rs.next()) {
                    if (!primero) json.append(",");
                    json.append("{\"id\":").append(rs.getInt("id"))
                        .append(",\"nombre\":\"").append(rs.getString("nombre"))
                        .append("\",\"placa\":\"").append(rs.getString("placa"))
                        .append("\",\"lat\":").append(rs.getDouble("latitud"))
                        .append(",\"lon\":").append(rs.getDouble("longitud"))
                        .append("}");
                    primero = false;
                }
            } catch (Exception e) {
                System.err.println("Error listando conductores: " + e.getMessage());
            }
            json.append("]");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });
    }

    private static void inicializarBaseDeDatosNacional() {
        String urlServidor = "jdbc:mysql://" + HOST + ":" + PORT_DB + "?sslMode=VERIFY_IDENTITY";
        try (Connection conn = DriverManager.getConnection(urlServidor, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS motogo_db;");
            stmt.executeUpdate("USE motogo_db;");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS ciudades (id INT AUTO_INCREMENT PRIMARY KEY, nombre VARCHAR(100), departamento VARCHAR(100));");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS conductores (id INT AUTO_INCREMENT PRIMARY KEY, nombre VARCHAR(150), placa VARCHAR(20) UNIQUE, ciudad_id INT, latitud DECIMAL(10, 8), longitud DECIMAL(11, 8), disponible BOOLEAN DEFAULT TRUE);");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS solicitudes (id INT AUTO_INCREMENT PRIMARY KEY, latitud DECIMAL(10, 8), longitud DECIMAL(11, 8), destino VARCHAR(255), fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS viajes (id INT AUTO_INCREMENT PRIMARY KEY, conductor_id INT, latitud DECIMAL(10, 8), longitud DECIMAL(11, 8), destino VARCHAR(255), estado VARCHAR(30) DEFAULT 'PENDIENTE', creado_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
        } catch (Exception e) {
            System.err.println("Error inicializando BD: " + e.getMessage());
        }
    }

    private static void registrarSolicitudDemanda(double lat, double lon, String destino) {
        String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
        String query = "INSERT INTO solicitudes (latitud, longitud, destino) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setDouble(1, lat);
            pstmt.setDouble(2, lon);
            pstmt.setString(3, destino);
            pstmt.executeUpdate();
        } catch (Exception ignored) {}
    }

    private static int crearViajePendiente(int conductorId, double lat, double lon, String destino) {
        String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
        String query = "INSERT INTO viajes (conductor_id, latitud, longitud, destino, estado) VALUES (?, ?, ?, ?, 'PENDIENTE')";
        try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, conductorId);
            pstmt.setDouble(2, lat);
            pstmt.setDouble(3, lon);
            pstmt.setString(4, destino);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            System.err.println("Error creando viaje: " + e.getMessage());
        }
        return -1;
    }

    private static Map<String, Object> buscarConductorMasCercano(double pLat, double pLon) {
        String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
        Map<String, Object> mejorConductor = null;
        double menorDistancia = Double.MAX_VALUE;

        String query = "SELECT id, nombre, placa, latitud, longitud FROM conductores WHERE disponible = TRUE";
        try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String nombre = rs.getString("nombre");
                String placa = rs.getString("placa");
                double cLat = rs.getDouble("latitud");
                double cLon = rs.getDouble("longitud");
                double distancia = calcularHaversine(pLat, pLon, cLat, cLon);

                if (distancia < menorDistancia) {
                    menorDistancia = distancia;
                    mejorConductor = new HashMap<>();
                    mejorConductor.put("id", id);
                    mejorConductor.put("nombre", nombre);
                    mejorConductor.put("placa", placa);
                    mejorConductor.put("distancia", Math.round(distancia * 100.0) / 100.0);
                }
            }
        } catch (Exception e) {
            System.err.println("Error Haversine: " + e.getMessage());
        }

        if (mejorConductor == null) {
            String fallback = "SELECT id, nombre, placa, latitud, longitud FROM conductores ORDER BY id DESC LIMIT 1";
            try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(fallback)) {
                if (rs.next()) {
                    mejorConductor = new HashMap<>();
                    mejorConductor.put("id", rs.getInt("id"));
                    mejorConductor.put("nombre", rs.getString("nombre"));
                    mejorConductor.put("placa", rs.getString("placa"));
                    mejorConductor.put("distancia", 1.0);
                }
            } catch (Exception ignored) {}
        }
        return mejorConductor;
    }

    private static double calcularHaversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}