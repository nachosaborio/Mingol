package Evaluador;

public class Errado implements Objeto{
    String message;

    public Errado(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public ObjectType Type() {
        return ObjectType.ERROR;
    }

    @Override
    public String Inspect() {
        return "Error: " + message;
    }
}
