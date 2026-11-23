
package cl.duoc.reservastickets;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import java.nio.file.Path;

@Service
public class S3StorageService {

    private final S3Client cliente;
    private final String bucket;

    public S3StorageService(S3Client cliente, @Value("${app.s3.bucket}") String bucket) {
        this.cliente = cliente;
        this.bucket = bucket;
    }

    public String subir(Ticket ticket, Path archivo) {
        Long numero = ticket.numeroResumen != null ? ticket.numeroResumen : ticket.reservaId;
        if (numero == null) {
            throw new IllegalArgumentException("Falta el numero del resumen");
        }
        // Las modificaciones sobrescriben el objeto existente, incluidos los antiguos.
        String clave = ticket.claveS3 != null ? ticket.claveS3
            : numero + "/" + ticket.nombreArchivo;

        cliente.putObject(
            PutObjectRequest.builder()
                .bucket(bucket())
                .key(clave)
                .contentType("application/pdf")
                .build(),
            RequestBody.fromFile(archivo)
        );

        return clave;
    }

    public byte[] descargar(String clave) {
        return cliente.getObjectAsBytes(
            software.amazon.awssdk.services.s3.model.GetObjectRequest
                .builder().bucket(bucket()).key(clave).build()
        ).asByteArray();
    }

    public void eliminar(String clave) {
        cliente.deleteObject(
            software.amazon.awssdk.services.s3.model.DeleteObjectRequest
                .builder().bucket(bucket()).key(clave).build()
        );
    }

    private String bucket() {
        if (bucket == null || bucket.isBlank())
            throw new IllegalStateException("Falta S3_BUCKET");
        return bucket;
    }

}
