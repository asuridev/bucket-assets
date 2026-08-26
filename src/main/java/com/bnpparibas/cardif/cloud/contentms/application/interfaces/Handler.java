package com.bnpparibas.cardif.cloud.contentms.application.interfaces;

/**
 * Raiz de los handlers. El registro automatico lee de aqui el tipo generico del
 * mensaje que cada handler atiende.
 *
 * @param <T> tipo del mensaje que atiende
 */
public interface Handler<T extends Dispatchable> {
}
