package Parser.Funciones;

import Lexer.Token;
import Parser.Expression;
import Parser.Identifier;
import Parser.Integral;
import Parser.Interfaces.IPrefixParseFn;

public class ParseInteger extends IPrefixParseFn{
    private Token currentToken;

    public void setCurrentToken(Token currentToken) {
        this.currentToken = currentToken;
    }
    
    @Override
    public Expression Function() {
        assert currentToken != null;
        Integral integral = new Integral(currentToken);
        try{
            integral.setToken(currentToken);
            integral.setValue(Integer.parseInt(currentToken.getLiteral()));
        }
        catch(ClassCastException e){
            System.err.println("Conversion invalida a entero: " + currentToken.getLiteral());
            return null;
        }
        
        return integral;
    }
}
