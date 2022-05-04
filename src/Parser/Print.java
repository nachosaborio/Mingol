package Parser;

import Lexer.Token;
import java.util.ArrayList;

public class Print extends Expression{
    ArrayList<Expression> identificadores = new ArrayList<>();
    String texto;
    Expression newline;

    public Print(Token token) {
        super(token);
    }
    
    public void AddIdentificador(Expression id){
        identificadores.add(id);
    }

    public ArrayList<Expression> getIdentificadores() {
        return identificadores;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }

    public Expression getNewline() {
        return newline;
    }

    public void setNewline(Expression newline) {
        this.newline = newline;
    }
    
    
    @Override
    public String toString(){
        String out = "";
        
        if(identificadores.size() > 0){
            for(Expression iden : identificadores){
                out += ", " + iden.toString();
            }
        }
        if(texto != null){
            out += texto;
        }
        if(newline != null){
            out += newline.toString();
        }
        return out;
    }
}