-- ============================================================================
-- 19_jobs.sql
-- Seccion 5 del diseno (parte 12): los 5 JOBs de DBMS_SCHEDULER.
--
-- Requiere haber ejecutado antes el script 18 (PKG_TAREAS).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Se puede volver a ejecutar: borra los JOBs que ya existan y los crea de
--      nuevo.
--   4. Esperado: 4 lineas "OK" de prueba y despues 5 JOBs TRUE / SCHEDULED.
--
-- LOS JOBS
--   JOB_ECOMERS_COLA        cada 30 s    PKG_TAREAS.procesar_cola
--   JOB_ECOMERS_EXPIRAR     cada minuto  PKG_TAREAS.expirar_pedidos
--   JOB_ECOMERS_CARRITOS    cada hora    PKG_TAREAS.avisar_carritos_abandonados
--   JOB_ECOMERS_ERP         cada hora    PKG_TAREAS.revisar_sincronizacion_erp (al minuto 30)
--   JOB_ECOMERS_CIERRE      00:10        PKG_TAREAS.cerrar_dias_pendientes + purgar_cola
--
-- DECISIONES DE DISENO
--   * La fecha de inicio va en America/Bogota. El calendario de un JOB se evalua
--     en la zona de su fecha de inicio: asi "00:10" es medianoche de Colombia
--     aunque la sesion que crea el JOB tenga otra zona.
--   * Un mismo JOB nunca corre dos veces a la vez: si una ejecucion tarda mas que
--     el intervalo, el Scheduler espera a que termine. Por eso no hace falta
--     ningun bloqueo propio para evitar ejecuciones solapadas.
--   * Bitacora: los JOBs usan la clase DEFAULT_JOB_CLASS, que registra TODAS las
--     ejecuciones en USER_SCHEDULER_JOB_RUN_DETAILS (unas 4.300 filas al dia por
--     los dos JOBs frecuentes). El nivel de la clase es un minimo: un JOB puede
--     registrar mas, no menos, asi que fijar LOGGING_FAILED_RUNS en el JOB no
--     sirve (por eso este script ya no lo hace). Oracle purga la bitacora sola
--     a los 30 dias (LOG_HISTORY), asi que el volumen se queda estable. Si algun
--     dia molesta, la solucion es una clase propia creada por SYSTEM.
--   * Los JOBs corren como ECOMERS (su duenio), que es lo que permite que
--     PKG_TAREAS sea interno y no se le de a ningun rol.
--   * Si una tarea falla (-20210, -20211), la ejecucion queda FAILED con el
--     mensaje, pero el JOB sigue programado: la siguiente ejecucion lo intenta
--     de nuevo.
--   * Los JOBs se crean deshabilitados, se prueban y solo entonces se habilitan:
--     si una prueba falla, el script se detiene y no queda nada corriendo.
--   * La prueba ejecuta una vez, en esta sesion, los JOBs de cola,
--     expiracion, carritos y ERP. Con la base sin datos no hacen nada, solo
--     comprueban que corren. El de CIERRE NO se ejecuta a mano: escribiria el
--     cierre real de ayer (CIERRE_DIARIO es de solo insercion).
--
-- OPERACION
--   Pausar un JOB:    EXEC DBMS_SCHEDULER.DISABLE('JOB_ECOMERS_COLA');
--   Reanudarlo:       EXEC DBMS_SCHEDULER.ENABLE('JOB_ECOMERS_COLA');
--   Ver fallos:       SELECT job_name, log_date, status, error#, additional_info
--                       FROM user_scheduler_job_run_details
--                      WHERE status <> 'SUCCEEDED' ORDER BY log_date DESC;
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 19_jobs.sql: comprobando requisitos

DECLARE
  v_existentes NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name = 'PKG_TAREAS'
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Falta PKG_TAREAS valido: ejecuta antes el script 18.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM session_privs
   WHERE privilege = 'CREATE JOB';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'ECOMERS no tiene el privilegio CREATE JOB: revisa el script 01.');
  END IF;
END;
/

PROMPT >> creando los JOBs

DECLARE
  PROCEDURE crear_job(
    p_nombre     IN VARCHAR2,
    p_accion     IN VARCHAR2,
    p_intervalo  IN VARCHAR2,
    p_comentario IN VARCHAR2
  ) IS
    v_existe NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_existe FROM user_scheduler_jobs WHERE job_name = p_nombre;
    IF v_existe > 0 THEN
      DBMS_SCHEDULER.DROP_JOB(job_name => p_nombre, force => TRUE);
    END IF;

    DBMS_SCHEDULER.CREATE_JOB(
      job_name        => p_nombre,
      job_type        => 'PLSQL_BLOCK',
      job_action      => p_accion,
      start_date      => SYSTIMESTAMP AT TIME ZONE 'America/Bogota',
      repeat_interval => p_intervalo,
      enabled         => FALSE,
      comments        => p_comentario);

    -- Se crean deshabilitados: se prueban primero y se habilitan al final.
  END crear_job;
BEGIN
  crear_job('JOB_ECOMERS_COLA',
            'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.procesar_cola; END;',
            'FREQ=SECONDLY;INTERVAL=30',
            'Rescata eventos atascados y aplica las notificaciones de pago.');

  crear_job('JOB_ECOMERS_EXPIRAR',
            'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.expirar_pedidos; END;',
            'FREQ=MINUTELY;INTERVAL=1',
            'Expira los pedidos sin pagar y libera su stock.');

  crear_job('JOB_ECOMERS_CARRITOS',
            'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.avisar_carritos_abandonados; END;',
            'FREQ=HOURLY;INTERVAL=1;BYMINUTE=0;BYSECOND=0',
            'Encola el aviso de los carritos abandonados.');

  crear_job('JOB_ECOMERS_ERP',
            'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.revisar_sincronizacion_erp; END;',
            'FREQ=HOURLY;INTERVAL=1;BYMINUTE=30;BYSECOND=0',
            'Encola la sincronizacion con el ERP de los pedidos pagados que no la tienen.');

  crear_job('JOB_ECOMERS_CIERRE',
            'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.cerrar_dias_pendientes; n := PKG_TAREAS.purgar_cola; END;',
            'FREQ=DAILY;BYHOUR=0;BYMINUTE=10;BYSECOND=0',
            'Cierre diario (hora de Bogota) y purga de la cola procesada.');
END;
/
PROMPT >> JOBs creados (aun deshabilitados)

-- ============================================================================
-- PRUEBA: antes de habilitarlos, ejecuta una vez en esta sesion los 4 JOBs que no
-- escriben datos reales con la base vacia. Esperado: 4 lineas "OK".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_fallos NUMBER := 0;

  PROCEDURE probar(p_job IN VARCHAR2) IS
  BEGIN
    DBMS_SCHEDULER.RUN_JOB(job_name => p_job, use_current_session => TRUE);
    DBMS_OUTPUT.PUT_LINE('OK: ' || p_job || ' corrio sin error');
  EXCEPTION
    WHEN OTHERS THEN
      v_fallos := v_fallos + 1;
      DBMS_OUTPUT.PUT_LINE('FALLO: ' || p_job || ' -> ' || SQLERRM);
  END probar;
BEGIN
  probar('JOB_ECOMERS_COLA');
  probar('JOB_ECOMERS_EXPIRAR');
  probar('JOB_ECOMERS_CARRITOS');
  probar('JOB_ECOMERS_ERP');
  IF v_fallos > 0 THEN
    RAISE_APPLICATION_ERROR(-20003,
      v_fallos || ' JOB(s) fallaron en la prueba: quedan creados pero DESHABILITADOS.');
  END IF;
END;
/

BEGIN
  FOR j IN (SELECT job_name FROM user_scheduler_jobs WHERE job_name LIKE 'JOB_ECOMERS%') LOOP
    DBMS_SCHEDULER.ENABLE(j.job_name);
  END LOOP;
END;
/
PROMPT >> JOBs habilitados

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT job_name, enabled, state, repeat_interval,
       TO_CHAR(next_run_date, 'YYYY-MM-DD HH24:MI:SS TZR') AS proxima_ejecucion
  FROM user_scheduler_jobs
 WHERE job_name LIKE 'JOB_ECOMERS%'
 ORDER BY job_name;                                                          -- 5 filas, TRUE, SCHEDULED

-- Pasado un minuto, esta consulta debe devolver 0 filas (ninguna ejecucion fallida):
-- SELECT job_name, log_date, status, error#, additional_info
--   FROM user_scheduler_job_run_details
--  WHERE job_name LIKE 'JOB_ECOMERS%' AND status <> 'SUCCEEDED';

-- ============================================================================
-- DESHACER (solo desarrollo): quita el comentario para borrar los 5 JOBs.
-- ============================================================================
-- BEGIN
--   FOR j IN (SELECT job_name FROM user_scheduler_jobs WHERE job_name LIKE 'JOB_ECOMERS%') LOOP
--     DBMS_SCHEDULER.DROP_JOB(job_name => j.job_name, force => TRUE);
--   END LOOP;
-- END;
-- /
