# CDY2204 Semana 5 Sistema de eventos y resumenes de compra

API REST con Java 17 y Spring Boot 4.0.9 para gestionar eventos, registrar compras y generar resúmenes PDF. Spring Security protege todos los endpoints de negocio mediante access tokens de Azure AD B2C con el permiso `eventos.access`.

## Resumenes de compra

Cada resumen utiliza el número de su reserva de compra como `numeroResumen`. Su identificador técnico `id` sigue siendo un UUID y se utiliza en las rutas de la API. El archivo contiene el número del resumen, evento, comprador, fecha, cantidad, precio unitario y total.

El proceso tiene dos operaciones separadas:

1. `POST /api/tickets/generar/{reservaId}` crea el PDF físico y registra sus metadatos. La respuesta incluye `numeroResumen`, `nombreArchivo` e `id`; `claveS3` es nula hasta la subida.
2. `POST /api/tickets/{id}/subir` sube el archivo generado al bucket configurado. La carpeta de S3 se llama igual que `numeroResumen` y contiene el PDF cuyo nombre figura en `nombreArchivo`.

`GET /api/tickets/{id}/descargar` permite guardar el PDF en el computador. Antes de la subida lee el archivo local; después lo obtiene desde S3. `PUT /api/tickets/{id}` regenera el PDF con un detalle corregido y, si ya fue subido, actualiza el mismo objeto de S3. Las claves antiguas se conservan al modificar resúmenes de la versión anterior.

## Endpoints

| Método | Ruta | Función |
|---|---|---|
| GET | `/api/eventos` | Listar eventos |
| POST | `/api/eventos` | Crear un evento |
| POST | `/api/reservas` | Registrar una compra |
| GET | `/api/eventos/{id}/estadisticas` | Consultar ventas del evento |
| POST | `/api/tickets/generar/{reservaId}` | Generar el resumen PDF local |
| POST | `/api/tickets/{id}/subir` | Subir el resumen a S3 |
| GET | `/api/tickets/historial` | Consultar los resúmenes |
| PUT | `/api/tickets/{id}` | Corregir el detalle y actualizar el PDF |
| GET | `/api/tickets/{id}/descargar` | Descargar un PDF |
| GET | `/api/tickets/descargar` | Descargar un ZIP filtrado por `eventoId` o `usuario` |
| DELETE | `/api/tickets/{id}` | Eliminar el objeto S3, los metadatos y el PDF local |

Ejemplo del cuerpo de una corrección:

```json
{"detalle":"Ingreso por puerta norte"}
```

## Autenticacion

La API funciona sin sesiones y requiere un encabezado Bearer con un access token. El backend valida la firma RSA con las claves públicas de B2C, el emisor, las fechas, la audiencia de la API y el flujo `B2C_1_eventos_signupsignin`. El permiso requerido es `eventos.access`.

- Sin token o con un token inválido: HTTP 401.
- Token válido sin el permiso requerido: HTTP 403.
- Un ID token destinado al cliente no reemplaza al access token de la API.

Los valores públicos del registro de eventos están configurados en `application.properties`. Se pueden sustituir mediante `B2C_ISSUER`, `B2C_JWKS_URI`, `B2C_AUDIENCE` y `B2C_POLICY`.

## Configuracion de almacenamiento

| Variable | Uso |
|---|---|
| `S3_BUCKET` | Nombre del bucket para las operaciones de subida, descarga y eliminación |
| `AWS_REGION` | Región AWS; valor predeterminado `us-east-1` |
| `TICKETS_DIR` | Directorio persistente de los PDF; valor predeterminado `./data/tickets` |

El SDK utiliza su cadena estándar de credenciales. En EC2 puede utilizar el rol IAM de la instancia. No se almacenan claves AWS ni tokens en el código.

La base H2 de la aplicación se guarda en `./data/reservas`. El contenedor conserva los montajes del proyecto anterior: `/app/data` para H2 y `/tickets` para los PDF en EFS.

## Compilacion y pruebas

```bash
mvn verify
```

Las pruebas de seguridad ejecutan solicitudes HTTP contra Spring Boot con JWT firmados mediante claves efímeras y un servidor local de claves públicas. Las pruebas de resúmenes verifican la creación de la compra, el PDF descargable, su número, el total, la carpeta de S3, la actualización del archivo, la descarga ZIP y la eliminación. Las bases y los archivos de prueba son temporales; S3 está simulado y no se accede al bucket real.

## Ejecucion y despliegue

```bash
java -jar target/reservas-tickets-0.0.1-SNAPSHOT.jar
```

El workflow de GitHub Actions ejecuta `mvn verify`, construye la imagen Docker, la publica en Docker Hub y define el despliegue en el runner EC2 existente. La comprobación de arranque espera HTTP 401 en `/api/eventos`, porque la ruta requiere autenticación. La validación funcional con token se realiza por separado.


## Despliegue y demostración S5 verificados

API Gateway: `https://74ah7suova.execute-api.us-east-1.amazonaws.com/lab`. Once operaciones protegidas con JWT y `eventos.access`. Evidencia real: `evidencias/gateway-s3-real.json` (14 solicitudes, 21 comprobaciones, cero errores). Colección reproducible en `postman/`; definición Gateway en `infra/`. Instrucciones de grabación y entrega en `GUIA_ENTREGA_Y_VIDEO.txt`.

La integración pública usa HTTP hacia EC2:8080; el cliente usa HTTPS hacia Gateway. Spring Security también exige JWT en acceso directo. Esta configuración de laboratorio no impide saltarse Gateway con un token válido.
