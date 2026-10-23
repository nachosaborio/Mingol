package Errores;

// Mensaje estructurado para el archivo de errores (línea, categoría, código, texto, lexema)
public class Mensaje {

    // Categorías del mensaje; el orden define la prioridad al listarlos
    public enum Categoria { LEXICO, SINTACTICO, SEMANTICO, ADVERTENCIA }

    // 307 y 308 no pertenecen a ninguna línea
    public static final int SIN_LINEA = 0;

    // Texto fijo de la advertencia de instrucción ALGOL no soportada (REQ-ALGOL-01)
    private static final String TEXTO_ADVERTENCIA = "instrucción no es soportada por esta versión";

    private final int linea;            // línea tal como se detectó (incluida la n+1)
    private final Categoria categoria;
    private final Integer codigo;       // null en las advertencias
    private final String texto;         // sin punto final
    private final String lexema;

    // Constructor privado: se crean con Error(...) o Advertencia(...)
    private Mensaje(int linea, Categoria categoria, Integer codigo, String texto, String lexema) {
        this.linea = linea;
        this.categoria = categoria;
        this.codigo = codigo;
        this.texto = texto;
        this.lexema = lexema;
    }

    // Error con código 1xx/2xx/3xx; la categoría sale de codigo / 100
    public static Mensaje Error(int linea, int codigo, String texto, String lexema) {
        Categoria categoria;
        switch (codigo / 100) {
            case 1: categoria = Categoria.LEXICO; break;
            case 2: categoria = Categoria.SINTACTICO; break;
            case 3: categoria = Categoria.SEMANTICO; break;
            default:
                // Protege la tabla de códigos: solo 100–399
                throw new IllegalArgumentException("Código de error fuera de rango (100-399): " + codigo);
        }
        return new Mensaje(linea, categoria, codigo, texto, lexema);
    }

    // Advertencia de instrucción ALGOL no soportada (sin código)
    public static Mensaje Advertencia(int linea, String lexema) {
        return new Mensaje(linea, Categoria.ADVERTENCIA, null, TEXTO_ADVERTENCIA, lexema);
    }

    // Verdadero si es un ERROR (impide invocar a68g); falso si es advertencia
    public boolean EsError() {
        return categoria != Categoria.ADVERTENCIA;
    }

    // Texto final: "ERROR NNN: texto [lexema]." o la advertencia con su lexema
    public String Formatear() {
        if (EsError()) {
            return String.format("ERROR %03d: %s [%s].", codigo, texto, lexema);
        }
        return "Advertencia: " + texto + " [" + lexema + "].";
    }

    // Getters
    public int getLinea() { return linea; }
    public Categoria getCategoria() { return categoria; }
    public Integer getCodigo() { return codigo; }
    public String getTexto() { return texto; }
    public String getLexema() { return lexema; }
}
