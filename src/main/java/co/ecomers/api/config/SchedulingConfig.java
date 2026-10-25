package co.ecomers.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita las tareas programadas (consumidores de la cola, ver scheduler.ColaScheduler). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
