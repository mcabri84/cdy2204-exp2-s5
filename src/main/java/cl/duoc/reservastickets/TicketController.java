package cl.duoc.reservastickets;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final ReservaRepository reservas;
    private final EventoRepository eventos;
    private final TicketRepository tickets;
    private final S3StorageService almacenamiento;
    private final ResumenPdfService pdf;

    public TicketController(ReservaRepository reservas, EventoRepository eventos,
                            TicketRepository tickets, S3StorageService almacenamiento,
                            ResumenPdfService pdf) {
        this.reservas = reservas;
        this.eventos = eventos;
        this.tickets = tickets;
        this.almacenamiento = almacenamiento;
        this.pdf = pdf;
    }

    @PostMapping("/generar/{reservaId}")
    public ResponseEntity<Ticket> generar(@PathVariable Long reservaId) throws IOException {
        Reserva reserva = reservas.findById(reservaId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva no encontrada"));
        Evento evento = eventos.findById(reserva.eventoId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
        Ticket ticket = new Ticket();
        ticket.id = UUID.randomUUID().toString();
        ticket.reservaId = reserva.id;
        ticket.numeroResumen = reserva.id;
        ticket.eventoId = evento.id;
        ticket.usuario = reserva.usuario;
        ticket.nombreArchivo = "resumen-" + ticket.numeroResumen + "-" + ticket.id + ".pdf";
        ticket.fechaGeneracion = LocalDateTime.now();
        pdf.generar(ticket, reserva, evento);
        return ResponseEntity.status(HttpStatus.CREATED).body(tickets.save(ticket));
    }

    @PostMapping("/{id}/subir")
    public Ticket subir(@PathVariable String id) {
        Ticket ticket = tickets.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Resumen no encontrado"));
        if (!Files.isRegularFile(pdf.archivo(ticket))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "El archivo del resumen no esta disponible para subir");
        }
        ticket.claveS3 = almacenamiento.subir(ticket, pdf.archivo(ticket));
        return tickets.save(ticket);
    }
}
