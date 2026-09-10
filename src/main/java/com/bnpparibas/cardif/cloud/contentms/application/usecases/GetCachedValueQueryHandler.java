package com.bnpparibas.cardif.cloud.contentms.application.usecases;

import com.bnpparibas.cardif.cloud.contentms.application.annotations.ApplicationComponent;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.QueryHandler;
import com.bnpparibas.cardif.cloud.contentms.application.queries.GetCachedValueQuery;
import com.bnpparibas.cardif.cloud.contentms.domain.values.ValueGenerator;

/**
 * Le pide el valor al puerto y lo devuelve. Nada mas.
 *
 * <p>La ausencia de codigo aqui es el objetivo, no un descuido: toda la cache vive en el
 * decorador del puerto, asi que este caso de uso no sabe que Redis existe y seguiria igual si
 * se cambiara de tecnologia de cache o se quitara del todo.
 */
@ApplicationComponent
public class GetCachedValueQueryHandler implements QueryHandler<GetCachedValueQuery, String> {

    private final ValueGenerator valueGenerator;

    public GetCachedValueQueryHandler(ValueGenerator valueGenerator) {
        this.valueGenerator = valueGenerator;
    }

    @Override
    public String handle(GetCachedValueQuery query) {
        return valueGenerator.valueFor(query.id());
    }
}
