package com.motogo;

import io.javalin.Javalin;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class Main {

    private static final String HOST = "gateway01.sa-east-1.prod.aws.tidbcloud.com";
    private static final String PORT_DB = "4000";
    private static final String USER = "VqAp2wi3ep6JvL1.root";
    private static final String PASSWORD = "TU_CONTRASEÑA_AQUÍ"; // Reemplaza con tu contraseña real

    public static void main(String[] args) {
        // 1. Inicializar la base de datos y tablas automáticamente en la nube
        inicializarBaseDeDatosNacional();

        // 2. Obtener el puerto dinámico asignado por Render (o usar 8080 por defecto local)
        int puertoApp = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            puertoApp = Integer.parseInt(portEnv);
        }

        // 3. Configurar Javalin sirviendo la carpeta estática /public y arrancando en el puerto correcto
        var app = Javalin.create(config -> {
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
        }).start(puertoApp);

        // Habilitar CORS de manera universal y segura
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "*");
        });

        System.out.println("¡Servidor MotoGo Backend Nacional Activo en el puerto " + puertoApp + "!");

        // 4. Endpoint principal para solicitar viaje con Haversine a nivel nacional
        app.post("/api/solicitar-viaje", ctx -> {
            String latStr = ctx.queryParam("lat");
            String lonStr = ctx.queryParam("lon");
            String destino = ctx.queryParam("destino");

            if (latStr == null || lonStr == null) {
                ctx.status(400).json(Map.of("mensaje", "Faltan coordenadas GPS del pasajero."));
                return;
            }

            double pasajeroLat = Double.parseDouble(latStr);
            double pasajeroLon = Double.parseDouble(lonStr);

            Map<String, Object> conductorAsignado = buscarConductorMasCercano(pasajeroLat, pasajeroLon);

            if (conductorAsignado != null) {
                ctx.json(Map.of(
                    "exito", true,
                    "conductor_asignado", conductorAsignado.get("nombre"),
                    "placa", conductorAsignado.get("placa"),
                    "distancia_km", conductorAsignado.get("distancia"),
                    "destino", destino != null ? destino : "Destino general"
                ));
            } else {
                ctx.status(404).json(Map.of("mensaje", "No hay unidades disponibles en este momento a nivel nacional."));
            }
        });

        // 5. Endpoint para el registro y conexión en tiempo real de conductores reales
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

                ctx.json(Map.of("exito", true, "mensaje", "Conductor registrado y activo correctamente."));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("exito", false, "error", e.getMessage()));
            }
        });
    }

    private static void inicializarBaseDeDatosNacional() {
        String urlServidor = "jdbc:mysql://" + HOST + ":" + PORT_DB + "?sslMode=VERIFY_IDENTITY";
        
        try (Connection conn = DriverManager.getConnection(urlServidor, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS motogo_db;");
            stmt.executeUpdate("USE motogo_db;");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS ciudades (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "nombre VARCHAR(100) NOT NULL, " +
                    "departamento VARCHAR(100) NOT NULL, " +
                    "activo BOOLEAN DEFAULT TRUE);");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS conductores (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "nombre VARCHAR(150) NOT NULL, " +
                    "placa VARCHAR(20) NOT NULL UNIQUE, " +
                    "ciudad_id INT, " +
                    "latitud DECIMAL(10, 8) NOT NULL, " +
                    "longitud DECIMAL(11, 8) NOT NULL, " +
                    "disponible BOOLEAN DEFAULT TRUE, " +
                    "FOREIGN KEY (ciudad_id) REFERENCES ciudades(id));");

            stmt.executeUpdate("INSERT IGNORE INTO ciudades (id, nombre, departamento) VALUES " +
                    "(1, 'Huaral', 'Lima'), " +
                    "(2, 'Lima Centro', 'Lima'), " +
                    "(3, 'Arequipa', 'Arequipa'), " +
                    "(4, 'Trujillo', 'La Libertad');");

            System.out.println("Base de datos motogo_db verificada y lista en TiDB Cloud.");

        } catch (Exception e) {
            System.err.println("Error al conectar o inicializar TiDB Cloud: " + e.getMessage());
        }
    }

    private static Map<String, Object> buscarConductorMasCercano(double pLat, double pLon) {
        String urlDb = "jdbc:mysql://" + HOST + ":" + PORT_DB + "/motogo_db?sslMode=VERIFY_IDENTITY";
        Map<String, Object> mejorConductor = null;
        double menorDistancia = Double.MAX_VALUE;

        String query = "SELECT nombre, placa, latitud, longitud FROM conductores WHERE disponible = TRUE";

        try (Connection conn = DriverManager.getConnection(urlDb, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                String nombre = rs.getString("nombre");
                String placa = rs.getString("placa");
                double cLat = rs.getDouble("latitud");
                double cLon = rs.getDouble("longitud");

                double distancia = calcularHaversine(pLat, pLon, cLat, cLon);

                if (distancia < menorDistancia) {
                    menorDistancia = distancia;
                    mejorConductor = new HashMap<>();
                    mejorConductor.put("nombre", nombre);
                    mejorConductor.put("placa", placa);
                    mejorConductor.put("distancia", Math.round(distancia * 100.0) / 100.0);
                }
            }

        } catch (Exception e) {
            System.err.println("Error en búsqueda por Haversine: " + e.getMessage());
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
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}