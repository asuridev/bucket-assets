package com.bnpparibas.cardif.cloud.contentms.infrastructure.values;

import com.bnpparibas.cardif.cloud.contentms.domain.values.ValueGenerator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador real del puerto: genera un valor nuevo en cada llamada.
 *
 * <p>Ocupa el sitio que en la rama principal ocupa el adaptador del object storage — el lado
 * caro y lento al que se quiere evitar ir. Aqui no es caro ni lento, pero si tiene la propiedad
 * que hace visible la cache: <b>llamarlo dos veces con el mismo id da dos valores distintos</b>.
 * Cuando la cache funciona, la segunda llamada no llega hasta aqui.
 *
 * <p>Deja traza en el log a proposito: ver esta linea una sola vez por id, con varias
 * peticiones hechas, es la prueba directa de que la cache esta interceptando.
 */
@Component(RandomValueGenerator.BEAN_NAME)
public class RandomValueGenerator implements ValueGenerator {

    /**
     * El decorador inyecta este bean por nombre. Sin el, los dos implementan el mismo puerto y
     * Spring no sabria cual pasarle a cual.
     */
    public static final String BEAN_NAME = "randomValueGenerator";

    private static final Logger log = LoggerFactory.getLogger(RandomValueGenerator.class);

    @Override
    public String valueFor(String id) {
        String value = UUID.randomUUID().toString();
        log.info("Valor GENERADO para id '{}' (esto no deberia repetirse mientras viva la cache)",
                id);
        return value;
    }
}
