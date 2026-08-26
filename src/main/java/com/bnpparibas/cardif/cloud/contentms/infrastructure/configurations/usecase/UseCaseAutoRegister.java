package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.usecase;

import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Dispatchable;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Handler;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Descubre los handlers al arrancar y los registra en el {@link UseCaseContainer}.
 *
 * <p>El tipo de mensaje se deduce del primer argumento generico de la interfaz
 * {@code Handler} que implementa cada handler, de modo que anadir un caso de uso no
 * obliga a tocar ninguna clase de configuracion.
 */
@Component
public class UseCaseAutoRegister {

    private static final Logger log = LoggerFactory.getLogger(UseCaseAutoRegister.class);

    private final UseCaseContainer container;
    private final List<Handler<?>> handlers;

    public UseCaseAutoRegister(UseCaseContainer container, List<Handler<?>> handlers) {
        this.container = container;
        this.handlers = handlers;
    }

    @PostConstruct
    void registerAll() {
        for (Handler<?> handler : handlers) {
            Class<? extends Dispatchable> messageType = messageTypeOf(handler);
            container.register(messageType, handler);
            log.debug("Handler {} registrado para {}", handler.getClass().getSimpleName(),
                    messageType.getSimpleName());
        }
        log.info("Casos de uso registrados: {}", container.size());
    }

    /**
     * El mensaje es el primer argumento generico de la interfaz {@code Handler} que
     * el handler implementa. Se recorren las interfaces del proxy y de la clase real
     * porque un handler con un aspecto encima llega envuelto.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Dispatchable> messageTypeOf(Handler<?> handler) {
        for (Class<?> type = targetClassOf(handler); type != null; type = type.getSuperclass()) {
            for (Type genericInterface : type.getGenericInterfaces()) {
                if (!(genericInterface instanceof ParameterizedType parameterized)) {
                    continue;
                }
                if (!(parameterized.getRawType() instanceof Class<?> raw)
                        || !Handler.class.isAssignableFrom(raw)) {
                    continue;
                }
                Type[] arguments = parameterized.getActualTypeArguments();
                if (arguments.length > 0 && arguments[0] instanceof Class<?> messageType
                        && Dispatchable.class.isAssignableFrom(messageType)) {
                    return (Class<? extends Dispatchable>) messageType;
                }
            }
        }
        throw new IllegalStateException("No se pudo deducir el mensaje que atiende "
                + handler.getClass().getName() + ": debe implementar CommandHandler, "
                + "ReturningCommandHandler o QueryHandler con un tipo concreto");
    }

    /** Desenvuelve el proxy de AOP, si lo hay, para llegar a la clase real. */
    private static Class<?> targetClassOf(Handler<?> handler) {
        return org.springframework.aop.support.AopUtils.getTargetClass(handler);
    }
}
