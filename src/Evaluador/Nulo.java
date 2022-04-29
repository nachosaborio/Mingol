package Evaluador;


public class Nulo implements Objeto{

    @Override
    public ObjectType Type() {
        return ObjectType.NULL;
    }

    @Override
    public String Inspect() {
        return "null";
    }
}
