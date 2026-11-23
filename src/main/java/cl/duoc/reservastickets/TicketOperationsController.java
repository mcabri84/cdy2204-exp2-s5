package cl.duoc.reservastickets;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@RestController
@RequestMapping("/api/tickets")
public class TicketOperationsController {

    private final TicketRepository tickets;
    private final ReservaRepository reservas;
    private final EventoRepository eventos;
    private final S3StorageService storage;
    private final ResumenPdfService pdf;

    public TicketOperationsController(
            TicketRepository tickets,
            ReservaRepository reservas,
            EventoRepository eventos,
            S3StorageService storage, ResumenPdfService pdf) {
        this.tickets = tickets;
        this.reservas = reservas;
        this.eventos = eventos;
        this.storage = storage;
        this.pdf = pdf;
    }

    private Ticket buscar(String id) {
        return tickets.findById(id).orElseThrow(() ->
            new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Ticket no encontrado"));
    }

    private List<Ticket> filtrar(Long eventoId, String usuario) {
        return tickets.findAll().stream()
            .filter(t -> eventoId == null ||
                    eventoId.equals(t.eventoId))
            .filter(t -> usuario == null ||
                    usuario.equals(t.usuario))
            .toList();
    }

    @GetMapping("/historial")
    public List<Ticket> historial(
            @RequestParam(required = false) Long eventoId,
            @RequestParam(required = false) String usuario) {
        return filtrar(eventoId, usuario);
    }

    @GetMapping("/{id}/descargar")
    public ResponseEntity<byte[]> descargar(@PathVariable String id) throws IOException {
        Ticket t = buscar(id);

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + t.nombreArchivo + "\"")
            .body(contenido(t));
    }

    @GetMapping("/descargar")
    public ResponseEntity<byte[]> descargarGrupo(
            @RequestParam(required = false) Long eventoId,
            @RequestParam(required = false) String usuario) throws IOException {

        if (eventoId == null && (usuario == null || usuario.isBlank()))
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Indique eventoId o usuario");

        List<Ticket> lista = filtrar(eventoId, usuario);

        if (lista.isEmpty())
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No hay tickets");

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida)) {
            for (Ticket t : lista) {
                zip.putNextEntry(new ZipEntry(t.nombreArchivo));
                zip.write(contenido(t));
                zip.closeEntry();
            }
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"tickets.zip\"")
            .body(salida.toByteArray());
    }

    public record CambioDetalle(String detalle) {}

    @PutMapping("/{id}")
    public Ticket modificar(@PathVariable String id,
                             @RequestBody CambioDetalle cambio) {

        Ticket t = buscar(id);

        if (cambio == null || cambio.detalle() == null ||
                cambio.detalle().isBlank())
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Detalle obligatorio");

        Reserva r = reservas.findById(t.reservaId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Reserva no encontrada"));

        Evento e = eventos.findById(t.eventoId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Evento no encontrado"));

        t.detalle = cambio.detalle().substring(
            0, Math.min(120, cambio.detalle().length()));

        try {
            pdf.generar(t, r, e);
            if (t.claveS3 != null) {
                t.claveS3 = storage.subir(t, pdf.archivo(t));
            }
            return tickets.save(t);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudo actualizar el resumen PDF", ex);
        }
    }

    private byte[] contenido(Ticket ticket) throws IOException {
        if (ticket.claveS3 != null) {
            return storage.descargar(ticket.claveS3);
        }
        try {
            return Files.readAllBytes(pdf.archivo(ticket));
        } catch (NoSuchFileException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Archivo del resumen no encontrado", ex);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable String id) {
        Ticket t = buscar(id);

        if (t.claveS3 != null)
            storage.eliminar(t.claveS3);

        tickets.delete(t);

        try {
            Files.deleteIfExists(pdf.archivo(t));
        } catch (IOException ignored) {
            // El archivo temporal puede limpiarse posteriormente.
        }
    }
}
