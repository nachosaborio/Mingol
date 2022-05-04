package Parser;

import Lexer.Token;

public class For extends Expression{
    Expression ciclo;
    Expression minima;
    Expression incremento;
    Expression maxima;
    Block comands;

    public For(Token token) {
        super(token);
    }

    public void setCiclo(Expression ciclo) {
        this.ciclo = ciclo;
    }

    public void setMinima(Expression minima) {
        this.minima = minima;
    }

    public void setIncremento(Expression incremento) {
        this.incremento = incremento;
    }

    public void setMaxima(Expression maxima) {
        this.maxima = maxima;
    }

    public void setComands(Block comands) {
        this.comands = comands;
    }

    public Expression getCiclo() {
        return ciclo;
    }

    public Expression getMinima() {
        return minima;
    }

    public Expression getIncremento() {
        return incremento;
    }

    public Expression getMaxima() {
        return maxima;
    }

    public Block getComands() {
        return comands;
    }
    
    @Override
    public String toString(){
        String out = "FOR " + ciclo.toString() +" FROM " + minima.toString() + "BY " + incremento.toString()
                + " TO " + maxima.toString() + " DO " + comands.toString() + " OD;";
        
        return out;
    }
}