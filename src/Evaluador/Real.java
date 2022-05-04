package Evaluador;

public class Real implements Objeto{
    
    double value;

    public Real(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }
    
    @Override
    public ObjectType Type() {
        return ObjectType.REAL;
    }

    @Override
    public String Inspect() {
        return Double.toString(value);
    }
}
