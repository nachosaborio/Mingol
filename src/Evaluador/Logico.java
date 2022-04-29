package Evaluador;

public class Logico implements Objeto{
    boolean value;

    public Logico(boolean value) {
        this.value = value;
    }

    public boolean getValue() {
        return value;
    }
    
    @Override
    public ObjectType Type() {
        return ObjectType.BOOLEAN;
    }

    @Override
    public String Inspect() {
        return Boolean.toString(value);
    }
}
