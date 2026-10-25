package co.ecomers.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import co.ecomers.api.repository.oracle.BaseDatosCliente;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * La arquitectura en capas, comprobada en cada build:
 *
 * <pre>
 *  controller (presentacion) -> service (negocio) -> repository (persistencia)  -> Oracle
 *                                               \-> client (Wompi, ERP, correo)
 *  scheduler -> service
 * </pre>
 *
 * Ademas, cada area de la persistencia solo usa la conexion de SU usuario de Oracle.
 */
@AnalyzeClasses(packages = "co.ecomers.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule capas = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Presentacion").definedBy("..controller..")
            .layer("Negocio").definedBy("..service..")
            .layer("Persistencia").definedBy("..repository..")
            .layer("Clientes").definedBy("..client..")
            .layer("Tareas").definedBy("..scheduler..")
            .whereLayer("Presentacion").mayNotBeAccessedByAnyLayer()
            .whereLayer("Tareas").mayNotBeAccessedByAnyLayer()
            .whereLayer("Negocio").mayOnlyBeAccessedByLayers("Presentacion", "Tareas")
            .whereLayer("Persistencia").mayOnlyBeAccessedByLayers("Negocio")
            .whereLayer("Clientes").mayOnlyBeAccessedByLayers("Negocio");

    @ArchTest
    static final ArchRule negocioNoConoceHttp = noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web..");

    @ArchTest
    static final ArchRule tiendaSoloComoCliente = noClasses().that().resideInAPackage("..repository.tienda..")
            .should().dependOnClassesThat().areAssignableTo(BaseDatosEmpleado.class)
            .orShould().dependOnClassesThat().areAssignableTo(BaseDatosIntegracion.class);

    @ArchTest
    static final ArchRule backofficeSoloComoEmpleado = noClasses().that().resideInAPackage("..repository.backoffice..")
            .should().dependOnClassesThat().areAssignableTo(BaseDatosCliente.class)
            .orShould().dependOnClassesThat().areAssignableTo(BaseDatosIntegracion.class);

    @ArchTest
    static final ArchRule integracionSoloComoIntegracion = noClasses().that().resideInAPackage("..repository.integracion..")
            .should().dependOnClassesThat().areAssignableTo(BaseDatosCliente.class)
            .orShould().dependOnClassesThat().areAssignableTo(BaseDatosEmpleado.class);
}
