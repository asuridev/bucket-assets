package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.usecase;

import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Command;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.CommandHandler;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Query;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.QueryHandler;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.ReturningCommand;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.ReturningCommandHandler;
import org.springframework.stereotype.Component;

/**
 * Punto unico de entrada a los casos de uso. Los controllers solo conocen esta clase.
 *
 * <p>A diferencia del mediador del proyecto de referencia, este NO abre transacciones:
 * ContentMS no tiene base de datos, asi que no hay {@code PlatformTransactionManager}
 * que inyectar ni frontera transaccional que definir. Si algun dia se anade
 * persistencia, este es el sitio donde ponerla — no los handlers.
 */
@Component
public class UseCaseMediator {

    private final UseCaseContainer container;

    public UseCaseMediator(UseCaseContainer container) {
        this.container = container;
    }

    @SuppressWarnings("unchecked")
    public <R, Q extends Query<R>> R dispatch(Q query) {
        QueryHandler<Q, R> handler = (QueryHandler<Q, R>) container.resolve(query.getClass());
        return handler.handle(query);
    }

    @SuppressWarnings("unchecked")
    public <C extends Command> void dispatch(C command) {
        CommandHandler<C> handler = (CommandHandler<C>) container.resolve(command.getClass());
        handler.handle(command);
    }

    @SuppressWarnings("unchecked")
    public <R, C extends ReturningCommand<R>> R dispatch(C command) {
        ReturningCommandHandler<C, R> handler =
                (ReturningCommandHandler<C, R>) container.resolve(command.getClass());
        return handler.handle(command);
    }
}
