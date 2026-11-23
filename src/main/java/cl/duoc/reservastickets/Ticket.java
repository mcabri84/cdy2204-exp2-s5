package cl.duoc.reservastickets;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class Ticket {
    @Id
    public String id;
    public Long reservaId;
    // El resumen utiliza el numero de la reserva de compra que documenta.
    public Long numeroResumen;
    public Long eventoId;
    public String usuario;
    public String nombreArchivo;
    public String detalle;
    public String claveS3;
    public LocalDateTime fechaGeneracion;

    public Ticket() {}
}
