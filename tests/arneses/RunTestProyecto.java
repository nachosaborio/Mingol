// Arnés de pruebas de preservación (spec critical-compiler-bugs, tarea 2).
// Invoca por reflexión el método privado Mingol.Mingol.TestProyecto(String), que es
// exactamente lo que ejecuta Mingol.main, pero SIN la llamada posterior a
// Runtime.exec("cmd /c start cmd /k C:\\Algol\\a68g.exe ..."), que abre una ventana de consola
// nueva cada vez que un fuente queda sin errores. No modifica nada en src/.
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class RunTestProyecto {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        // Misma normalización de extensión que Mingol.main
        if (path.split("\\.").length == 1) {
            path += ".mingol";
        }
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            Object ok = m.invoke(null, path);
            System.out.println("TestProyecto=" + ok);
        } catch (InvocationTargetException e) {
            // Propaga la excepción original (p. ej. NumberFormatException) igual que main
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            throw e;
        }
    }
}
