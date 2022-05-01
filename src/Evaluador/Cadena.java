package Evaluador;

public class Cadena implements Objeto{
    
    private String value;

    public Cadena(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public ObjectType Type() {
        return ObjectType.STRING;
    }

    @Override
    public String Inspect() {
        return value;
    }
    
}
