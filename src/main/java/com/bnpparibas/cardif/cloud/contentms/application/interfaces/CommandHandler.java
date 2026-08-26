package com.bnpparibas.cardif.cloud.contentms.application.interfaces;

/**
 * Handler de un comando sin retorno.
 *
 * @param <C> comando que atiende
 */
public interface CommandHandler<C extends Command> extends Handler<C> {

    void handle(C command);
}
