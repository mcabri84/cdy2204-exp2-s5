package cl.duoc.reservastickets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=jdbc:h2:mem:resumen-tests",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.s3.bucket=resumenes-prueba"})
class ResumenCompraIntegrationTests {
    @Value("${local.server.port}")
    private int port;
    @MockitoBean
    private S3Client s3;
    // La validacion criptografica se prueba en SecurityIntegrationTests.
    @MockitoBean
    private JwtDecoder decoder;
    private static Path carpeta;
    private final Map<String, byte[]> objetos = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) throws Exception {
        carpeta = Files.createTempDirectory("cdy2204-resumenes-");
        registry.add("app.tickets-dir", () -> carpeta.toString());
    }

    @BeforeEach
    void prepararServiciosExternos() {
        Jwt jwt = Jwt.withTokenValue("token-prueba")
            .header("alg", "RS256").subject("usuario-prueba")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .claim("scp", "eventos.access").build();
        when(decoder.decode("token-prueba")).thenReturn(jwt);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenAnswer(invocation -> {
                PutObjectRequest request = invocation.getArgument(0);
                assertEquals("resumenes-prueba", request.bucket());
                assertEquals("application/pdf", request.contentType());
                RequestBody body = invocation.getArgument(1);
                try (var input = body.contentStreamProvider().newStream()) {
                    objetos.put(request.key(), input.readAllBytes());
                }
                return PutObjectResponse.builder().eTag("prueba").build();
            });
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            assertEquals("resumenes-prueba", request.bucket());
            byte[] bytes = objetos.get(request.key());
            assertNotNull(bytes, "La descarga debe usar una clave previamente subida");
            return ResponseBytes.fromByteArray(GetObjectResponse.builder()
                .contentType("application/pdf").build(), bytes);
        });
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            DeleteObjectRequest request = invocation.getArgument(0);
            assertEquals("resumenes-prueba", request.bucket());
            objetos.remove(request.key());
            return DeleteObjectResponse.builder().build();
        });
    }

    private HttpResponse<byte[]> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer token-prueba")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Map<String, Object> json(HttpResponse<byte[]> response, int status) {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        return JsonParserFactory.getJsonParser()
            .parseMap(new String(response.body(), StandardCharsets.UTF_8));
    }

    private Map<String, Object> generarResumen() throws Exception {
        var evento = json(request("POST", "/api/eventos",
            "{\"nombre\":\"Concierto de prueba\",\"fecha\":\"2026-12-01\",\"precio\":15000}"), 201);
        var reserva = json(request("POST", "/api/reservas",
            "{\"eventoId\":" + evento.get("id") + ",\"usuario\":\"comprador-prueba\",\"cantidad\":2}"), 201);
        assertEquals(30000, ((Number) reserva.get("total")).intValue());
        var resumen = json(request("POST", "/api/tickets/generar/" + reserva.get("id"), null), 201);
        assertEquals(reserva.get("id"), resumen.get("numeroResumen"));
        assertNull(resumen.get("claveS3"));
        return resumen;
    }

    private String textoPdf(byte[] bytes) throws Exception {
        try (var doc = Loader.loadPDF(bytes)) {
            assertEquals(1, doc.getNumberOfPages());
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void cicloGenerarDescargarSubirModificarYBorrar() throws Exception {
        var resumen = generarResumen();
        String id = (String) resumen.get("id");
        Path archivo = carpeta.resolve((String) resumen.get("nombreArchivo"));
        assertTrue(Files.isRegularFile(archivo));
        var descargaLocal = request("GET", "/api/tickets/" + id + "/descargar", null);
        assertEquals(200, descargaLocal.statusCode());
        assertTrue(descargaLocal.headers().firstValue("Content-Disposition").orElse("")
            .contains("attachment"));
        String texto = textoPdf(descargaLocal.body());
        assertTrue(texto.contains("RESUMEN DE COMPRA"));
        assertTrue(texto.contains("Numero de resumen: " + resumen.get("numeroResumen")));
        assertTrue(texto.contains("TOTAL: $30000"));
        verifyNoInteractions(s3);

        var subida = json(request("POST", "/api/tickets/" + id + "/subir", null), 200);
        String clave = (String) subida.get("claveS3");
        assertEquals(resumen.get("numeroResumen") + "/" + resumen.get("nombreArchivo"), clave);
        assertArrayEquals(Files.readAllBytes(archivo), objetos.get(clave));

        Files.delete(archivo);
        var descargaRemota = request("GET", "/api/tickets/" + id + "/descargar", null);
        assertEquals(200, descargaRemota.statusCode());
        assertArrayEquals(descargaLocal.body(), descargaRemota.body());

        var cambio = json(request("PUT", "/api/tickets/" + id,
            "{\"detalle\":\"Ingreso por puerta norte\"}"), 200);
        assertEquals(clave, cambio.get("claveS3"));
        assertEquals(1, objetos.size());
        assertTrue(textoPdf(objetos.get(clave)).contains("Detalle: Ingreso por puerta norte"));
        assertTrue(Files.isRegularFile(archivo));

        var zip = request("GET", "/api/tickets/descargar?eventoId=" + resumen.get("eventoId"), null);
        assertEquals(200, zip.statusCode());
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            assertEquals(resumen.get("nombreArchivo"), input.getNextEntry().getName());
            assertTrue(textoPdf(input.readAllBytes()).contains("Ingreso por puerta norte"));
            assertNull(input.getNextEntry());
        }

        assertEquals(204, request("DELETE", "/api/tickets/" + id, null).statusCode());
        assertFalse(Files.exists(archivo));
        assertTrue(objetos.isEmpty());
        assertEquals(404, request("GET", "/api/tickets/" + id + "/descargar", null).statusCode());
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void puedeCorregirYEliminarUnResumenSinSubirlo() throws Exception {
        var resumen = generarResumen();
        String id = (String) resumen.get("id");
        var cambio = json(request("PUT", "/api/tickets/" + id,
            "{\"detalle\":\"Correccion antes de subir\"}"), 200);
        assertNull(cambio.get("claveS3"));
        assertTrue(textoPdf(request("GET", "/api/tickets/" + id + "/descargar", null).body())
            .contains("Correccion antes de subir"));
        assertEquals(204, request("DELETE", "/api/tickets/" + id, null).statusCode());
        verifyNoInteractions(s3);
    }

    @Test
    void subirUnResumenInexistenteDevuelve404() throws Exception {
        assertEquals(404, request("POST", "/api/tickets/no-existe/subir", null).statusCode());
        verifyNoInteractions(s3);
    }

    @AfterAll
    static void limpiarArchivosDePrueba() throws Exception {
        if (carpeta != null) {
            try (var paths = Files.walk(carpeta)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
