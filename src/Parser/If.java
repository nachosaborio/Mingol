package Parser;

import Lexer.Token;


public class If extends Expression{
    Expression condition;
    Block consequence;
    Block alternative;

    public If(Token token, Expression condition, Block consequence, Block alternative) {
        super(token);
        this.condition = condition;
        this.consequence = consequence;
        this.alternative = alternative;
    }

    public If(Token token, Expression condition, Block consequence ) {
        this(token, condition, consequence, null);
    }

    public If(Token token) {
        this(token,null,null,null);
    }
    
    public Expression getCondition() {
        return condition;
    }

    public void setCondition(Expression condition) {
        this.condition = condition;
    }

    public Block getConsequence() {
        return consequence;
    }

    public void setConsequence(Block consequence) {
        this.consequence = consequence;
    }

    public Block getAlternative() {
        return alternative;
    }

    public void setAlternative(Block alternative) {
        this.alternative = alternative;
    }
    
    @Override
    public String toString(){
        String out = "IF " + condition.toString() +" THEN " + consequence.toString();
        
        if(alternative != null){
            out += " ELSE " + alternative.toString();
        }
        return out;
    }
}
