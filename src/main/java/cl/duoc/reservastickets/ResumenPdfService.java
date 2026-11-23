package cl.duoc.reservastickets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

@Service
public class ResumenPdfService {
    private final Path carpeta;

    public ResumenPdfService(@Value("${app.tickets-dir}") String carpeta) {
        this.carpeta = Path.of(carpeta).toAbsolutePath().normalize();
    }

    public Path archivo(Ticket ticket) {
        Path archivo = carpeta.resolve(ticket.nombreArchivo).normalize();
        if (!carpeta.equals(archivo.getParent())) {
            throw new IllegalArgumentException("Nombre de archivo invalido");
        }
        return archivo;
    }

    public void generar(Ticket ticket, Reserva reserva, Evento evento) throws IOException {
        Long numero = ticket.numeroResumen != null ? ticket.numeroResumen : reserva.id;
        List<String> lineas = new ArrayList<>(List.of(
            "RESUMEN DE COMPRA",
            "Numero de resumen: " + numero,
            "Reserva: " + reserva.id,
            "Evento: " + evento.nombre,
            "Fecha del evento: " + evento.fecha,
            "Comprador: " + reserva.usuario,
            "Fecha de compra: " + reserva.fechaCompra,
            "Entradas: " + reserva.cantidad,
            "Precio unitario: $" + reserva.precioUnitario,
            "TOTAL: $" + reserva.total));
        if (ticket.detalle != null && !ticket.detalle.isBlank()) {
            lineas.add("Detalle: " + ticket.detalle);
        }
        Files.createDirectories(carpeta);
        PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDDocument pdf = new PDDocument()) {
            PDPage pagina = new PDPage(PDRectangle.A4);
            pdf.addPage(pagina);
            try (PDPageContentStream contenido = new PDPageContentStream(pdf, pagina)) {
                float y = 780;
                for (String linea : lineas) {
                    String seguro = Normalizer.normalize(linea, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").replaceAll("[^\\x20-\\x7E]", "?");
                    StringBuilder fragmento = new StringBuilder();
                    for (char caracter : seguro.toCharArray()) {
                        if (fuente.getStringWidth(fragmento.toString() + caracter) / 1000 * 12 > 495) {
                            escribir(contenido, fuente, fragmento.toString(), y);
                            y -= 22;
                            fragmento.setLength(0);
                        }
                        fragmento.append(caracter);
                    }
                    escribir(contenido, fuente, fragmento.toString(), y);
                    y -= 28;
                }
            }
            pdf.save(archivo(ticket).toFile());
        }
    }

    private void escribir(PDPageContentStream contenido, PDType1Font fuente,
                          String texto, float y) throws IOException {
        contenido.beginText();
        contenido.setFont(fuente, 12);
        contenido.newLineAtOffset(50, y);
        contenido.showText(texto);
        contenido.endText();
    }
}
