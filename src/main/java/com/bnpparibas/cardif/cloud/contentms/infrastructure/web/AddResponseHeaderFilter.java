package com.bnpparibas.cardif.cloud.contentms.infrastructure.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Anade a <b>toda</b> respuesta las dos cabeceras que exige el escaneo de seguridad (ZAP).
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — impide que el navegador ignore el
 *       {@code Content-Type} declarado y adivine el tipo por el contenido.
 *   <li>{@code Cross-Origin-Resource-Policy: same-origin} — impide que otro origen incruste la
 *       respuesta.
 * </ul>
 *
 * <p>Es un filtro y no un {@code WebMvcConfigurer} a proposito: un filtro corre <b>fuera</b> del
 * {@code DispatcherServlet}, asi que las cabeceras salen tambien en lo que nunca llega a un
 * controller — los endpoints del actuator, los errores del contenedor y las respuestas que
 * produce {@link com.bnpparibas.cardif.cloud.contentms.infrastructure.rest.ApiExceptionHandler
 * ApiExceptionHandler}. Un interceptor de MVC no cubriria esos casos, que son justo los que el
 * escaner visita.
 *
 * <p>Procedencia: plantilla de despliegue de DevOps, starter {@code ap13002-content-ms-app-repo}
 * rama {@code release-v6.5.2}. Ver README seccion 8.
 */
@Component
public class AddResponseHeaderFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("Cross-Origin-Resource-Policy", "same-origin");

        chain.doFilter(request, response);
    }
}
