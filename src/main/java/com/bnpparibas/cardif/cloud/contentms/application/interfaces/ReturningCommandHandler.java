package com.bnpparibas.cardif.cloud.contentms.application.interfaces;

/**
 * Handler de un comando con retorno.
 *
 * @param <C> comando que atiende
 * @param <R> tipo del resultado
 */
public interface ReturningCommandHandler<C extends ReturningCommand<R>, R> extends Handler<C> {

    R handle(C command);
}
