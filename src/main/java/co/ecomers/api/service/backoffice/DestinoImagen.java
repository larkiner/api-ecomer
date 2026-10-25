package co.ecomers.api.service.backoffice;

/** A qué carpeta del bucket va una imagen: cada tipo de recurso tiene la suya, para mantener ordenado el almacenamiento. */
public enum DestinoImagen {
    PRODUCTOS("productos"),
    CATEGORIAS("categorias");

    private final String carpeta;

    DestinoImagen(String carpeta) {
        this.carpeta = carpeta;
    }

    public String carpeta() {
        return carpeta;
    }
}
