package com.bnpparibas.cardif.cloud.contentms.infrastructure.web;

import com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation.CorrelationContext;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation.CorrelationIds;
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
 * Abre el contexto de correlacion en cada peticion HTTP y devuelve en la respuesta los
 * identificadores del HU-211 que hayan entrado.
 *
 * <p>El filtro NO rechaza la peticion si faltan ni si vienen malformados: eso es cosa del
 * controller, que los declara con {@code @RequestHeader} y produce un 400 con el cuerpo de
 * error del contrato. Aqui el trazado es best-effort — un filtro vive fuera del
 * {@code DispatcherServlet}, asi que lo que lanzara no lo veria el
 * {@code ApiExceptionHandler}. Se ordena casi al principio de la cadena por esa misma
 * razon: que hasta las peticiones malformadas queden trazadas en el log.
 *
 * <p><b>El GET de descarga es distinto en dos cosas</b>, porque alli
 * {@code correlation_id} y {@code request_id} son OPCIONALES ({@code _p} no existe):
 *
 * <ul>
 *   <li>no se genera correlacion cuando falta — el contrato del GET dice que quien no
 *       manda cabeceras tampoco las recibe de vuelta, y generar una seria devolver algo
 *       que el cliente no pidio;</li>
 *   <li>un valor presente pero que no sea un UUID canonico se descarta en vez de entrar
 *       al MDC: el controller lo convertira en un 400 acto seguido, y mientras tanto el
 *       log no se ensucia con un identificador que no traza nada.</li>
 * </ul>
 *
 * <p>En el POST las tres cabeceras siguen siendo obligatorias y su valor se toma tal cual.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "correlation_id";
    public static final String HEADER_REQUEST_ID = "request_id";
    public static final String HEADER_PARTNER_ID = "_p";

    private static final String DOWNLOAD_PATH = "/v1/content-loaded";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_CORRELATION_ID);
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        String partnerId = request.getHeader(HEADER_PARTNER_ID);

        if (isDownload(request)) {
            correlationId = uuidOrNull(correlationId);
            requestId = uuidOrNull(requestId);
        } else if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            CorrelationContext.set(correlationId, requestId, partnerId);
            setIfPresent(response, HEADER_CORRELATION_ID, correlationId);
            setIfPresent(response, HEADER_REQUEST_ID, requestId);
            setIfPresent(response, HEADER_PARTNER_ID, partnerId);
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private static boolean isDownload(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && DOWNLOAD_PATH.equals(request.getRequestURI());
    }

    private static String uuidOrNull(String value) {
        return CorrelationIds.isUuid(value) ? value.trim() : null;
    }

    private static void setIfPresent(HttpServletResponse response, String header, String value) {
        if (value != null && !value.isBlank()) {
            response.setHeader(header, value);
        }
    }
}
