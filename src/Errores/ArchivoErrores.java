package Errores;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Escribe el archivo <Programa>-errores.txt (REQ-ERR-02/03/04/05)
public class ArchivoErrores {

    // Sangría de cada mensaje: empieza en la columna 8 (8 espacios)
    private static final String SANGRIA = "        ";

    // Clase de utilidades: no se instancia
    private ArchivoErrores() {
    }

    // Escribe la copia numerada del fuente con los mensajes bajo su línea (REQ-ERR-02/03)
    public static void Escribir(String ruta, List<String> lineas, List<Mensaje> mensajes) throws IOException {
        int n = lineas.size();

        // Copia ordenada (List.sort es estable): por clave de línea y luego por categoría
        List<Mensaje> ordenados = new ArrayList<>(mensajes);
        ordenados.sort(Comparator
                .comparingInt((Mensaje m) -> Clave(m, n))
                .thenComparingInt(m -> m.getCategoria().ordinal()));

        try (FileWriter w = new FileWriter(ruta)) {
            int k = 0;

            // Cada línea del fuente, numerada a 5 dígitos, seguida de sus mensajes
            for (int i = 1; i <= n; i++) {
                w.write(String.format("%05d", i) + " " + lineas.get(i - 1) + "\n");
                while (k < ordenados.size() && Clave(ordenados.get(k), n) == i) {
                    w.write(SANGRIA + ordenados.get(k).Formatear() + "\n");
                    k++;
                }
            }

            // Bloque final: 307/308 y, si el fuente está vacío, los de línea fuera de rango
            while (k < ordenados.size()) {
                w.write(SANGRIA + ordenados.get(k).Formatear() + "\n");
                k++;
            }
        }
    }

    // Clave de escritura; Mensaje.linea no se modifica (decisión del usuario sobre D1)
    private static int Clave(Mensaje m, int n) {
        int linea = m.getLinea();
        // SIN_LINEA (o cualquier valor no positivo) va al bloque final
        if (linea < 1) {
            return Integer.MAX_VALUE;
        }
        // Dentro del fuente: la propia línea
        if (linea <= n) {
            return linea;
        }
        // Fuera de rango (n+1): bajo la última línea; con fuente vacío, al bloque final
        return n >= 1 ? n : Integer.MAX_VALUE;
    }
}
