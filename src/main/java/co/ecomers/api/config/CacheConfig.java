package co.ecomers.api.config;

import co.ecomers.api.service.tienda.CatalogoService;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache en memoria (Caffeine) del catalogo publico, acotado en tamanio y con expiracion: no hace
 * falta barrerlo a mano. Para varias instancias, cambiar por un cache distribuido.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(@Value("${app.cache.catalogo-ttl-seg:60}") long ttlSegundos) {
        var manager = new CaffeineCacheManager(CatalogoService.CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSegundos))
                .maximumSize(1_000));
        return manager;
    }
}
