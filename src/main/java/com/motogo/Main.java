package com.motogo;

import io.javalin.Javalin;

public class Main {
    public static void main(String[] args) {
        // Render asigna un puerto dinámico mediante la variable de entorno "PORT". 
        // Si se ejecuta de forma local, por defecto usará el puerto 7070.
        int port = getPort();

        // Inicializamos la instancia del servidor Javalin
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.anyHost(); // Permite peticiones desde cualquier origen (útil para la fase de pruebas)
                });
            });
        }).start(port);

        // Ruta de prueba para verificar que el servidor en Render está vivo y operando
        app.get("/", ctx -> {
            ctx.result("¡Servidor MotoGo Backend funcionando correctamente en la nube!");
        });

        // Endpoint básico de estado
        app.get("/health", ctx -> {
            ctx.status(200).json("{\"status\": \"OK\", \"message\": \"El backend operativo\"}");
        });

        System.out.println("==================================================");
        System.out.println(" Servidor MotoGo iniciado con éxito en el puerto: " + port);
        System.out.println("==================================================");
    }

    private static int getPort() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                return Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                System.out.println("Puerto inválido en variable de entorno, usando puerto por defecto: 7070");
            }
        }
        return 7070; // Puerto local por defecto
    }
}