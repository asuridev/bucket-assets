package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.usecase;

import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Dispatchable;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Handler;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Registro de handlers por tipo de mensaje. Lo puebla {@link UseCaseAutoRegister} al
 * arrancar y lo consulta {@link UseCaseMediator} al despachar.
 */
@Component
public class UseCaseContainer {

    private final Map<Class<? extends Dispatchable>, Handler<?>> handlers = new HashMap<>();

    /**
     * @throws IllegalStateException si dos handlers dicen atender el mismo mensaje:
     *         cual gana seria arbitrario, asi que se falla al arrancar en vez de
     *         elegir en silencio
     */
    public void register(Class<? extends Dispatchable> messageType, Handler<?> handler) {
        Handler<?> previous = handlers.putIfAbsent(messageType, handler);
        if (previous != null) {
            throw new IllegalStateException("Hay dos handlers para " + messageType.getName() + ": "
                    + previous.getClass().getName() + " y " + handler.getClass().getName());
        }
    }

    /**
     * @throws IllegalStateException si nadie atiende ese mensaje: es un fallo de
     *         cableado, no una condicion de negocio
     */
    public Handler<?> resolve(Class<? extends Dispatchable> messageType) {
        Handler<?> handler = handlers.get(messageType);
        if (handler == null) {
            throw new IllegalStateException("No hay handler registrado para " + messageType.getName());
        }
        return handler;
    }

    public int size() {
        return handlers.size();
    }
}
