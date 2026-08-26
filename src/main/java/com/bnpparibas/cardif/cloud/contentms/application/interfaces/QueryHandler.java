package com.bnpparibas.cardif.cloud.contentms.application.interfaces;

/**
 * Handler de una consulta.
 *
 * @param <Q> consulta que atiende
 * @param <R> tipo del resultado
 */
public interface QueryHandler<Q extends Query<R>, R> extends Handler<Q> {

    R handle(Q query);
}
