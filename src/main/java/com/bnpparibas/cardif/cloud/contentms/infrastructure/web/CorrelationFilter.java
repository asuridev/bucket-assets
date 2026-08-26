package com.bnpparibas.cardif.cloud.contentms.infrastructure.web;

import com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Abre el contexto de correlacion en cada peticion HTTP y devuelve los tres
 * identificadores del HU-211 en la respuesta.
 *
 * <p>El filtro NO rechaza la peticion si faltan: eso es cosa del controller, que los
 * declara con {@code @RequestHeader} y produce un 400 con el cuerpo de error del
 * contrato. Aqui solo se toma lo que haya, generando una correlacion si el cliente no
 * la manda, para que hasta las peticiones malformadas queden trazadas en el log.
 *
 * <p>Se ordena casi al principio de la cadena por esa misma razon.
 *
 * <p><b>Excepcion:</b> el GET de descarga se salta el filtro entero. Ese endpoint no
 * admite ninguna de las tres cabeceras ni de entrada ni de salida, y como el filtro
 * genera una correlacion cuando falta y la escribe en la respuesta, dejarlo pasar
 * seria devolver justo lo que el contrato del GET ya no lleva. A cambio, sus lineas
 * de log salen sin correlacion.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "correlation_id";
    public static final String HEADER_REQUEST_ID = "request_id";
    public static final String HEADER_PARTNER_ID = "_p";

    private static final String DOWNLOAD_PATH = "/v1/content-loaded";

    /** El GET de descarga va sin cabeceras de correlacion: ni se leen ni se devuelven. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && DOWNLOAD_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        String partnerId = request.getHeader(HEADER_PARTNER_ID);

        try {
            CorrelationContext.set(correlationId, requestId, partnerId);
            response.setHeader(HEADER_CORRELATION_ID, correlationId);
            setIfPresent(response, HEADER_REQUEST_ID, requestId);
            setIfPresent(response, HEADER_PARTNER_ID, partnerId);
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private static void setIfPresent(HttpServletResponse response, String header, String value) {
        if (value != null && !value.isBlank()) {
            response.setHeader(header, value);
        }
    }
}
