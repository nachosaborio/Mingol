package Evaluador;


public class Entero implements Objeto{
    int value;
    
    public Entero(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
    
    @Override
    public ObjectType Type() {
        return ObjectType.INTEGERS;
    }

    @Override
    public String Inspect() {
        return Integer.toString(value);
    }
}
