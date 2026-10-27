package Evaluador;

import java.util.HashMap;

// El mapa heredado (crudo) sigue guardando variables y marcas BEGIN/END (DM1)
public class Environment extends HashMap{

    // Variable -> tipo declarado ("INT", "REAL" o "CHAR") (DM2)
    private final HashMap<String, String> tipos = new HashMap<>();

    // Etiqueta -> nodo de su primera aparicion (espacio de nombres propio)
    private final HashMap<String, Object> etiquetas = new HashMap<>();

    // Es variable si lo guardado es un Objeto (un Errado tambien cuenta)
    public boolean EsVariable(String nombre) {
        return get(nombre) instanceof Objeto;
    }

    // Guarda el tipo declarado de una variable
    public void DeclararTipo(String nombre, String tipo) {
        tipos.put(nombre, tipo);
    }

    // Tipo declarado de la variable, o null si no tiene
    public String TipoDe(String nombre) {
        return tipos.get(nombre);
    }

    // Registra la etiqueta si no estaba (true); si ya estaba no la pisa (false)
    public boolean RegistrarEtiqueta(String nombre, Object nodo) {
        if (etiquetas.containsKey(nombre)) {
            return false;
        }
        etiquetas.put(nombre, nodo);
        return true;
    }

    // Nodo de la primera aparicion de la etiqueta, o null si no existe
    public Object PrimeraEtiqueta(String nombre) {
        return etiquetas.get(nombre);
    }

    // Indica si el nombre es una etiqueta registrada
    public boolean EsEtiqueta(String nombre) {
        return etiquetas.containsKey(nombre);
    }
}
