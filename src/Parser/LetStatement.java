package Parser;

import Lexer.Token;

public class LetStatement extends Statement{
    private Identifier name;
    private Expression value;


    //Sobrecargas
    public LetStatement(Token token, Identifier name, Expression value){
        super(token);
        this.name = name;
        this.value = value;
    }

    public LetStatement(Token token, Identifier name){
        this(token, name, null);
    }

    public LetStatement(Token token, Expression value){
        this(token, null, value);
    }

    public LetStatement(Token token){
        this(token, null, null);
    }

    public void SetName(Identifier name){
        this.name = name;
    }

    public void SetValue(Expression value){
        this.value = value;
    }

    @Override
    public String Str() {
        return String.format("%1$s %2$s = %3$s" , TokenLiteral(), name.Str(), value) ;
    }
}
