package Parser;

import java.util.ArrayList;
// Magnitudes de constantes sin depender de int/double (L4)
import java.math.BigDecimal;
import java.math.BigInteger;

// Mensajes estructurados para el archivo de errores
import Errores.Mensaje;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Interfaces.IInfixParseFn;
import Parser.Interfaces.IPrefixParseFn;
import java.util.HashMap;

public class Parser {

    public boolean hasErrors = false;
    public int line = 1;
    private boolean isEnd = false;
    private boolean isComment = false;
    private boolean errorBegin = false;
    // Corrección BUG 1: se eliminó el campo 'linea' porque nunca se incrementaba
    // (todos los mensajes decían "Linea 1"). Ahora los mensajes usan 'line',
    // que AdvanceToken() incrementa al consumir cada EOL.

    private Lexer lexer;
    private Token currentToken;
    private Token peekToken;
    // Token anterior del flujo (el currentToken que deja AdvanceToken); null al inicio
    private Token anterior;
    // Primer token del último comando (posición de comando); lo usa ParseAlgol (L7)
    private Token tokenDeComando;
    // Constante INTEGER/REAL precedida por un '-' unario (L4); se compara por identidad
    private Token constanteNegada;
    private ArrayList<String> errors;
    // Mensajes estructurados, uno por cada cadena de 'errors' y en el mismo orden
    private ArrayList<Mensaje> mensajes;
    private HashMap<MingolToken, IPrefixParseFn> prefixParseFns;
    private HashMap<MingolToken, IInfixParseFn> infixParseFns;
    private final HashMap<MingolToken, Precedence> PRECEDENCES = new HashMap<MingolToken, Precedence>() {
        {
            put(MingolToken.EQ, Precedence.EQUALS);
            put(MingolToken.NE, Precedence.EQUALS);
            put(MingolToken.LT, Precedence.LESSGREATER);
            put(MingolToken.GT, Precedence.LESSGREATER);
            put(MingolToken.LE, Precedence.LESSGREATER);
            put(MingolToken.GE, Precedence.LESSGREATER);
            put(MingolToken.ADDITION, Precedence.SUM);
            put(MingolToken.SUBSTRACTION, Precedence.SUM);
            put(MingolToken.DIVISION, Precedence.PRODUCT);
            put(MingolToken.MULTIPLICATION, Precedence.PRODUCT);
            put(MingolToken.LPAREN, Precedence.CALL);
        }
    };

    public Parser(Lexer lexer, Token currentToken, Token peekToken) {
        this.lexer = lexer;
        this.errors = new ArrayList<String>();
        // Lista paralela a 'errors' para el archivo de errores
        this.mensajes = new ArrayList<Mensaje>();
        prefixParseFns = RegisterPrefixFns();
        infixParseFns = RegisterInfixFns();

        AdvanceToken();
        AdvanceToken();
    }

    public Parser(Lexer lexer) {
        this(lexer, null, null);
    }

    public Program ParseProgram() {
        Program program = new Program(new ArrayList<Statement>());

        while (currentToken.getTokenType() != MingolToken.EOF) {
            Statement statement = ParseSatement();
            if (statement != null) {
                program.statements.add(statement);
            }
            AdvanceToken();
        }

        // Los errores léxicos (1xx) también invalidan el programa
        if (lexer.HayErrores()) {
            hasErrors = true;
        }
        return program;
    }

    public void AdvanceToken() {
        if(currentToken != null && currentToken.getTokenType().equals(MingolToken.EOL)){
            line++;
        }
        // Único lugar donde avanza el flujo: guarda el token que se deja
        anterior = currentToken;
        currentToken = peekToken;
        peekToken = lexer.NextToken();
    }

    // Posición de comando: inicio del archivo o después de EOL o ';'
    private boolean EsInicioDeComando() {
        return anterior == null
                || anterior.getTokenType() == MingolToken.EOL
                || anterior.getTokenType() == MingolToken.SEMICOLON;
    }

    private void checkSemicolon(MingolToken mingol) {
        if (peekToken.getTokenType() != MingolToken.EOL
                && peekToken.getTokenType() != MingolToken.EOF) {
            // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
            errors.add("\tLinea "+ line +" Error 202: no puede haber nada en la línea luego del token " + mingol);
            // Mensaje 202 con el token sobrante como lexema
            mensajes.add(Mensaje.Error(line, 202, "No puede haber nada en la línea después de " + mingol, Lexema(peekToken)));
            hasErrors = true;
        }
        AdvanceToken();
    }

    private Precedence CurrentPrecedence() {
        assert currentToken != null :
                "current token es null";
        Precedence precedence = PRECEDENCES.get(currentToken.getTokenType());
        if (precedence == null) {
            return Precedence.LOWEST;
        }
        return precedence;
    }

    private void ExpectedTokenError(MingolToken token) {
        // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
        String error = "\tLinea "+ line +" Error 204: Se esperaba un token de tipo " + token
                + " pero se obtuvo un " + peekToken.getTokenType();
        errors.add(error);
        // Mensaje 204 con el token siguiente (el obtenido) como lexema
        mensajes.add(Mensaje.Error(line, 204, "Se esperaba un token de tipo " + token
                + " pero se obtuvo " + peekToken.getTokenType(), Lexema(peekToken)));
        hasErrors = true;
    }

    // Constante entera con el rango de L4 (la usan ParseInteger y ParseFor).
    // Magnitud > 32768: el lexer ya dio el 108. 32768 sin '-' unario: 108 aquí.
    // Nunca devuelve null por rango (sin 201 en cascada); solo por una conversión fallida (102).
    private Integral ConstanteEntera(Token t, boolean negada) {
        Integral integral = new Integral(t);
        try {
            BigInteger m = new BigInteger(t.getLiteral());
            if (m.compareTo(Lexer.MAX_MAGNITUD_ENTERA) == 0 && !negada) {
                // Mensaje 108 (léxico) en la línea física de la constante
                errors.add("\tLinea "+ t.getLine() +" Error 108: Constante entera fuera de rango: " + t.getLiteral());
                mensajes.add(Mensaje.Error(t.getLine(), 108, "Constante entera fuera de rango", t.getLiteral()));
                hasErrors = true;
            }
            // Valor acotado a int para no desbordar
            integral.setValue(m.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue());
        } catch (NumberFormatException e) {
            // Se conserva el 102 controlado para una conversión que no sea de rango (3.8)
            errors.add("\tLinea "+ line +" Error 203: conversion invalida a entero: " + t.getLiteral());
            // Mensaje 102 (léxico): constante no convertible a entero
            mensajes.add(Mensaje.Error(line, 102, "Constante numérica no convertible a entero", t.getLiteral()));
            hasErrors = true;
            return null;
        }
        return integral;
    }

    // Igual que ExpectedTokenError, pero informa el token actual (el que ocupa el lugar del esperado)
    private void ExpectedCurrentTokenError(MingolToken token) {
        String error = "\tLinea "+ line +" Error 204: Se esperaba un token de tipo " + token
                + " pero se obtuvo un " + currentToken.getTokenType();
        errors.add(error);
        // Mensaje 204 con el token actual como lexema (EOF al final del archivo, con línea n+1)
        mensajes.add(Mensaje.Error(line, 204, "Se esperaba un token de tipo " + token
                + " pero se obtuvo " + currentToken.getTokenType(), Lexema(currentToken)));
        hasErrors = true;
    }

    // Lexema para el corchete: nunca un salto de línea ni vacío
    private String Lexema(Token t) {
        if ("\n".equals(t.getLiteral())) {
            return "EOL";
        }
        if (t.getLiteral() == null || t.getLiteral().isEmpty()) {
            return t.getTokenType().toString();
        }
        return t.getLiteral();
    }

    private boolean ExpectedToken(MingolToken token) {
        if (peekToken.getTokenType() == token) {
            AdvanceToken();
            return true;
        }
        ExpectedTokenError(token);
        return false;
    }

    public ArrayList<String> GetErrors() {
        return errors;
    }

    // Lista nueva: primero los mensajes del lexer y después los del parser
    // (los del parser siguen en el mismo orden que GetErrors())
    public ArrayList<Mensaje> GetMensajes() {
        ArrayList<Mensaje> todos = new ArrayList<>(lexer.GetMensajes());
        todos.addAll(mensajes);
        return todos;
    }

    private void JumpEOL() {
        while(currentToken.getTokenType().equals(MingolToken.EOL)){
            AdvanceToken();
        }
    }

    private Block ParseBlock() {
        assert currentToken != null :
                "current token es null";
        Block block = new Block(currentToken, new ArrayList<>());

        AdvanceToken();

        while (currentToken.getTokenType() != MingolToken.FI
                && currentToken.getTokenType() != MingolToken.ELSE
                && currentToken.getTokenType() != MingolToken.OD
                && currentToken.getTokenType() != MingolToken.EOL
                // Se detiene en EOF: antes el bucle no terminaba al final del archivo
                && currentToken.getTokenType() != MingolToken.EOF) {
            Statement statement = ParseSatement();

            if (statement != null) {
                block.AddStatement(statement);
            }
            AdvanceToken();
        }
        return block;
    }

    private ArrayList<Expression> ParseCallArguments() {
        ArrayList<Expression> arguments = new ArrayList<>();
        assert peekToken != null :
                "El peek token es null";

        if (peekToken.getTokenType().equals(MingolToken.RPAREN)) {
            AdvanceToken();
            return arguments;
        }

        AdvanceToken();
        Expression expression = ParseExpression(Precedence.LOWEST);

        if (expression != null) {
            arguments.add(expression);
        }

        while (peekToken.getTokenType() == MingolToken.COMMA) {
            AdvanceToken();
            AdvanceToken();
            expression = ParseExpression(Precedence.LOWEST);
            if (expression != null) {
                arguments.add(expression);
            }
        }

        if (!ExpectedToken(MingolToken.RPAREN)) {
            return null;
        }
        return arguments;
    }

    private Expression ParseExpression(Precedence precedence) {
        assert currentToken != null :
                "El current token es null";
        IPrefixParseFn prefixParseFn = prefixParseFns.get(currentToken.getTokenType());
        if (prefixParseFn == null) {
            // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
            String mensaje = "\tLinea "+ line +" Error 203: Token inválido: " + currentToken.getLiteral();
            errors.add(mensaje);
            // Mensaje 203: token que no puede iniciar una expresión
            mensajes.add(Mensaje.Error(line, 203, "Token que no puede iniciar una expresión", Lexema(currentToken)));
            hasErrors = true;
            return null;
        }
        Expression leftExpression = prefixParseFn.Function();
        // El prefijo ya registró su error: no se siguen aplicando infijos sobre null
        if (leftExpression == null) {
            return null;
        }

        assert peekToken != null :
                "Peek token es null";

        while ((peekToken.getTokenType() != MingolToken.SEMICOLON)
                && (precedence.ordinal() < PeekPrecedence().ordinal())) {
            IInfixParseFn infixParseFn = infixParseFns.get(peekToken.getTokenType());

            if (infixParseFn == null) {
                return leftExpression;
            }

            AdvanceToken();
            assert leftExpression != null :
                    "left expression es null";
            leftExpression = infixParseFn.Function(leftExpression);
        }
        return leftExpression;
    }

    private ExpressionStatement ParseExpressionStatement() {
        assert currentToken != null :
                "El current token es null";
        ExpressionStatement expressionStatement = new ExpressionStatement(currentToken);
        expressionStatement.setExpression(ParseExpression(Precedence.LOWEST));

        assert peekToken != null :
                "El peek token es null";

        if (peekToken.getTokenType() == MingolToken.SEMICOLON) {
            AdvanceToken();
        }

        return expressionStatement;
    }

    private LetStatement ParseLetStatement() {
        assert currentToken != null :
                "El current token es null";
        LetStatement letStatement = new LetStatement(currentToken);

        MingolToken esperado = currentToken.getTokenType();

        if (!ExpectedToken(MingolToken.IDENT)) {
            return null;
        }
        letStatement.SetName((Identifier) prefixParseFns.get(MingolToken.IDENT).Function());

        if (!ExpectedToken(MingolToken.ASSIGN)) {
            return null;
        }

        AdvanceToken();
        letStatement.SetValue(ParseExpression(Precedence.LOWEST));

        assert peekToken != null :
                "Peek token es null";

        // Lexema del 201: el valor inicial o, si es null (prefijo fallido), el nombre de la variable
        String nombre201 = letStatement.getName().toString();
        String lexema201 = letStatement.getValue() != null ? letStatement.getValue().TokenLiteral() : nombre201;

        // Corrección BUG 1: los tres Error 201 usan 'line' (contador real) en lugar de 'linea'.
        switch (esperado) {
            case TYPESTRING:
                if (!(letStatement.getValue() instanceof StringLiteral)) {
                    errors.add("\tLinea "+ line +" Error 201: " + letStatement.getName().toString() + " no es un STRING");
                    // Mensaje 201: valor inicial que no es STRING
                    mensajes.add(Mensaje.Error(line, 201, "El valor inicial no corresponde al tipo STRING de la variable " + nombre201, lexema201));
                    hasErrors = true;
                    return null;
                }
                break;
            case TYPEINT:
                if (!(letStatement.getValue() instanceof Integral)) {
                    errors.add("\tLinea "+ line +" Error 201: " + letStatement.getName().toString() + " no es un INT");
                    // Mensaje 201: valor inicial que no es INT
                    mensajes.add(Mensaje.Error(line, 201, "El valor inicial no corresponde al tipo INT de la variable " + nombre201, lexema201));
                    hasErrors = true;
                    return null;
                }
                break;
            case TYPEREAL:
                if (!(letStatement.getValue() instanceof Decimal)) {
                    errors.add("\tLinea "+ line +" Error 201: " + letStatement.getName().toString() + " no es un REAL");
                    // Mensaje 201: valor inicial que no es REAL
                    mensajes.add(Mensaje.Error(line, 201, "El valor inicial no corresponde al tipo REAL de la variable " + nombre201, lexema201));
                    hasErrors = true;
                    return null;
                }
                break;
        }

        if (!peekToken.getTokenType().equals(MingolToken.SEMICOLON)) {
            ExpectedToken(MingolToken.SEMICOLON);
            return null;
        }
        AdvanceToken();
        return letStatement;
    }

    private ReturnStatement ParseReturnStatement() {
        assert currentToken != null :
                "El current token es null";
        ReturnStatement returnStatement = new ReturnStatement(currentToken);
        AdvanceToken();
        while (currentToken.getTokenType() != MingolToken.SEMICOLON) {
            AdvanceToken();
        }
        return returnStatement;
    }

    private Statement ParseSatement() {
        assert currentToken != null :
                "El current token es null";
        // Posición de comando: se recuerda el token y, si es un IDENT que no
        // va seguido de ':=', no es identificador sino comando (L2): sin 103
        if (EsInicioDeComando()) {
            tokenDeComando = currentToken;
            if (currentToken.getTokenType() == MingolToken.IDENT
                    && peekToken.getTokenType() != MingolToken.ASSIGN) {
                lexer.RetirarMayusculas(currentToken);
            }
        }
        switch (currentToken.getTokenType()) {
            case TYPEINT:
            case TYPESTRING:
            case TYPECHAR:
            case TYPEREAL:
            case IDENT:
                return ParseLetStatement();
            case BEGIN: {
                // Se usa el token real (con su línea) en lugar de uno nuevo sin línea
                Token tokenBegin = currentToken;
                checkSemicolon(MingolToken.BEGIN);
                return new Statement(tokenBegin);
            }
            case END: {
                // Se usa el token real (con su línea) en lugar de uno nuevo sin línea
                Token tokenEnd = currentToken;
                checkSemicolon(MingolToken.END);
                return new Statement(tokenEnd);
            }
            case RETURN:
                return ParseReturnStatement();
            default:
                return ParseExpressionStatement();
        }
    }

    private Precedence PeekPrecedence() {
        assert peekToken != null :
                "Peek token es null";
        Precedence precedence = PRECEDENCES.get(peekToken.getTokenType());
        if (precedence == null) {
            return Precedence.LOWEST;
        }
        return precedence;
    }

    private HashMap<MingolToken, IPrefixParseFn> RegisterPrefixFns() {
        //Creación de las funciones
        //Parse identifier
        IPrefixParseFn ParseIdentifier = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                return new Identifier(currentToken, currentToken.getLiteral());
            }
        };

        IPrefixParseFn ParseEtiqueta = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                return new Etiqueta(currentToken, currentToken.getLiteral());
            }
        };

        //Parse Integer
        IPrefixParseFn ParseInteger = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                // Rango con '-' unario (L4): negada si es la constante que guardó ParsePrefixExpressions
                return ConstanteEntera(currentToken, currentToken == constanteNegada);
            }
        };

        IPrefixParseFn ParseReal = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                Decimal decimal = new Decimal(currentToken);
                try {
                    // Rango con '-' unario (L4); > 32768.99 ya lo reportó el lexer (no se duplica)
                    BigDecimal m = new BigDecimal(currentToken.getLiteral());
                    boolean negada = currentToken == constanteNegada;
                    if (m.compareTo(Lexer.MAX_MAGNITUD_REAL) <= 0
                            && m.compareTo(negada ? Lexer.MAX_REAL_NEGATIVO : Lexer.MAX_REAL_POSITIVO) > 0) {
                        // Mensaje 110 (léxico) en la línea física de la constante
                        errors.add("\tLinea "+ currentToken.getLine() +" Error 110: Constante real fuera de rango: " + currentToken.getLiteral());
                        mensajes.add(Mensaje.Error(currentToken.getLine(), 110, "Constante real fuera de rango", currentToken.getLiteral()));
                        hasErrors = true;
                    }
                    decimal.setValue(Double.parseDouble(currentToken.getLiteral()));
                } catch (NumberFormatException e) {
                    // parseDouble lanza NumberFormatException (no ClassCastException); antes no se capturaba
                    // y abortaba el compilador. Ahora se registra como error controlado (antes decia "entero").
                    errors.add("\tLinea "+ line +" Error 203: conversion invalida a real: " + currentToken.getLiteral());
                    // Mensaje 102 (léxico): constante no convertible a real
                    mensajes.add(Mensaje.Error(line, 102, "Constante numérica no convertible a real", currentToken.getLiteral()));
                    hasErrors = true;
                    return null;
                }
                return decimal;
            }
        };

        //Prefix expressions
        IPrefixParseFn ParsePrefixExpressions = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                Prefix prefixExpression = new Prefix(currentToken, currentToken.getLiteral());
                // '-' unario delante de una constante: se recuerda para el rango (L4)
                if (currentToken.getTokenType() == MingolToken.SUBSTRACTION
                        && (peekToken.getTokenType() == MingolToken.INTEGER
                            || peekToken.getTokenType() == MingolToken.REAL)) {
                    constanteNegada = peekToken;
                }
                AdvanceToken();
                prefixExpression.setRight(ParseExpression(Precedence.PREFIX));
                return prefixExpression;
            }
        };

        //Parse boolean
        IPrefixParseFn ParseBoolean = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "El current token es null";
                return new Booleano(currentToken, currentToken.getTokenType() == MingolToken.TRUE);
            }
        };

        //Parse grouped expression
        IPrefixParseFn ParseGroupedExpression = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                AdvanceToken();
                Expression expression = ParseExpression(Precedence.LOWEST);

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }
                return expression;
            }
        };

        //Parse if
        IPrefixParseFn ParseIf = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "current token es null";
                If ifExpression = new If(currentToken);
                if (!ExpectedToken(MingolToken.LPAREN)) {
                    return null;
                }

                AdvanceToken();
                ifExpression.setCondition(ParseExpression(Precedence.LOWEST));

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }

                if (!ExpectedToken(MingolToken.THEN)) {
                    return null;
                }

                checkSemicolon(MingolToken.THEN);
                ifExpression.setConsequence(ParseBlock());

                JumpEOL();

                if (currentToken.getTokenType().equals(MingolToken.ELSE)) {
                    checkSemicolon(MingolToken.ELSE);
                    ifExpression.setAlternative(ParseBlock());
                }
                JumpEOL();
                if (!currentToken.getTokenType().equals(MingolToken.FI)) {
                    // Falta FI: se registra el 204 en lugar de devolver null en silencio
                    ExpectedCurrentTokenError(MingolToken.FI);
                    return null;
                }
                while (currentToken.getTokenType().equals(MingolToken.EOL)) {
                    AdvanceToken();
                }
                JumpEOL();
                return ifExpression;
            }
        };

        //Parse for
        IPrefixParseFn ParseFor = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "current token es null";
                For forExpression = new For(currentToken);

                //FOR
                if (!ExpectedToken(MingolToken.IDENT)) {
                    return null;
                }
                forExpression.setCiclo(new Identifier(currentToken, currentToken.getLiteral()));
                JumpEOL();
                //FROM
                if (!ExpectedToken(MingolToken.FROM)) {
                    return null;
                }
                JumpEOL();
                if (!peekToken.getTokenType().equals(MingolToken.IDENT)
                        && !peekToken.getTokenType().equals(MingolToken.INTEGER)) {
                    ExpectedToken(MingolToken.INTEGER);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.IDENT)) {
                    forExpression.setMinima(new Identifier(currentToken, currentToken.getLiteral()));
                } else {
                    // Rango L4 sin NumberFormatException; null solo si la conversión falla (102): For.toString no tolera null
                    Integral minima = ConstanteEntera(currentToken, false);
                    if (minima == null) {
                        return null;
                    }
                    forExpression.setMinima(minima);
                }
                JumpEOL();
                //BY
                if (!ExpectedToken(MingolToken.BY)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)
                        && !peekToken.getTokenType().equals(MingolToken.INTEGER)) {
                    ExpectedToken(MingolToken.INTEGER);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.IDENT)) {
                    forExpression.setIncremento(new Identifier(currentToken, currentToken.getLiteral()));
                } else {
                    // Igual que FROM: constante con rango L4, null solo por 102
                    Integral incremento = ConstanteEntera(currentToken, false);
                    if (incremento == null) {
                        return null;
                    }
                    forExpression.setIncremento(incremento);
                }
                JumpEOL();
                //TO
                if (!ExpectedToken(MingolToken.TO)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)
                        && !peekToken.getTokenType().equals(MingolToken.INTEGER)) {
                    ExpectedToken(MingolToken.INTEGER);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.IDENT)) {
                    forExpression.setMaxima(new Identifier(currentToken, currentToken.getLiteral()));
                } else {
                    // Igual que FROM: constante con rango L4, null solo por 102
                    Integral maxima = ConstanteEntera(currentToken, false);
                    if (maxima == null) {
                        return null;
                    }
                    forExpression.setMaxima(maxima);
                }
                AdvanceToken();
                JumpEOL();
                //DO
                if (!currentToken.getTokenType().equals(MingolToken.DO)) {
                    ExpectedToken(MingolToken.DO);
                    return null;
                }
                forExpression.setComands(ParseBlock());

                return forExpression;
            }
        };

        IPrefixParseFn ParseStringLiteral = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "El current token es null";
                return new StringLiteral(currentToken, currentToken.getLiteral());
            }
        };

        IPrefixParseFn ParseRead = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Read read = new Read(currentToken);

                if (!ExpectedToken(MingolToken.LPAREN)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)) {
                    ExpectedToken(MingolToken.IDENT);
                    return null;
                }
                AdvanceToken();
                read.setIdentificador(new Identifier(currentToken, currentToken.getLiteral()));

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }
                if (!ExpectedToken(MingolToken.SEMICOLON)) {
                    return null;
                }

                return read;
            }
        };

        IPrefixParseFn ParsePrint = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Print print = new Print(currentToken);

                if (!ExpectedToken(MingolToken.LPAREN)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.LPAREN)
                        && !peekToken.getTokenType().equals(MingolToken.NEWLINE)
                        && !peekToken.getTokenType().equals(MingolToken.STRING)) {
                    return null;
                }

                switch (peekToken.getTokenType()) {
                    case LPAREN:
                        AdvanceToken();
                        if (!ExpectedToken(MingolToken.IDENT)) {
                            return null;
                        }
                        print.AddIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
                        while (!peekToken.getTokenType().equals(MingolToken.RPAREN)) {
                            if (!ExpectedToken(MingolToken.COMMA)) {
                                return null;
                            }
                            if (!ExpectedToken(MingolToken.IDENT)) {
                                return null;
                            }
                            print.AddIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
                        }
                        AdvanceToken();
                        break;
                    case NEWLINE:
                        print.setNewline(new Expression(currentToken));
                        AdvanceToken();
                        break;
                    case STRING:
                        print.setTexto(currentToken.getLiteral());
                        AdvanceToken();
                        break;
                }

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }

                if (!ExpectedToken(MingolToken.SEMICOLON)) {
                    return null;
                }

                return print;
            }
        };

        IPrefixParseFn ParseSkip = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Skip skip = new Skip(currentToken);

                if (!peekToken.getTokenType().equals(MingolToken.SEMICOLON)
                        && !peekToken.getTokenType().equals(MingolToken.EOL)) {
                    ExpectedToken(MingolToken.SEMICOLON);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.EOL)) {
                    if (!peekToken.getTokenType().equals(MingolToken.FI)
                            && peekToken.getTokenType().equals(MingolToken.OD)
                            && peekToken.getTokenType().equals(MingolToken.END)) {
                        ExpectedToken(MingolToken.SEMICOLON);
                        return null;
                    }
                    return skip;
                } else {
                    AdvanceToken();
                    if (!currentToken.getTokenType().equals(MingolToken.EOL)) {
                        ExpectedToken(MingolToken.SEMICOLON);
                        return null;
                    } else {
                        AdvanceToken();
                        return skip;
                    }
                }
            }
        };

        IPrefixParseFn ParseComments = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Token thisToken = currentToken;
                do {
                    AdvanceToken();
                } while (currentToken.getTokenType() != MingolToken.EOF
                        && currentToken.getTokenType() != MingolToken.COMMENT);
                return new Expression(thisToken);
            }
        };

        IPrefixParseFn ParseAlgol = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                // L7: TRUE/FALSE fuera de posición de comando siguen siendo Booleano, sin advertencia
                String literal = currentToken.getLiteral();
                if ((literal.equals("TRUE") || literal.equals("FALSE"))
                        && currentToken != tokenDeComando) {
                    return new Booleano(currentToken, literal.equals("TRUE"));
                }
                // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
                errors.add("\tLinea "+ line +" ADVERTENCIA: Instruccion " + currentToken.getLiteral() + " no soportada por esta version");
                // Advertencia ALGOL con el literal de la instrucción como lexema
                mensajes.add(Mensaje.Advertencia(line, currentToken.getLiteral()));
                Token thisToken = currentToken;
                while (currentToken.getTokenType() != MingolToken.EOL
                        && currentToken.getTokenType() != MingolToken.EOF) {
                    AdvanceToken();
                }
                return new Expression(thisToken);
            }
        };

        IPrefixParseFn ParseGoto = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Goto gouto = new Goto(currentToken);
                if (!ExpectedToken(MingolToken.IDENT)) {
                    return null;
                }
                gouto.setIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
                AdvanceToken();
                return gouto;
            }
        };

        IPrefixParseFn ParseIllegal = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
                errors.add("\tLinea "+ line +"Error 205: Token ilegal: " + currentToken.getLiteral());
                // Mensaje 101 (léxico): carácter ilegal
                mensajes.add(Mensaje.Error(line, 101, "Carácter ilegal", Lexema(currentToken)));
                hasErrors = true;
                return null;
            }
        };
        
        IPrefixParseFn ParseEOL = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                while(peekToken.getTokenType() == MingolToken.EOL){
                    AdvanceToken();
                }
                return new Expression(new Token(MingolToken.EOL, ""));
            }
        };

        //Se agregan las funciones
        HashMap<MingolToken, IPrefixParseFn> functions = new HashMap<MingolToken, IPrefixParseFn>();
        functions.put(MingolToken.IDENT, ParseIdentifier);
        functions.put(MingolToken.ETIQUETA, ParseEtiqueta);
        functions.put(MingolToken.INTEGER, ParseInteger);
        functions.put(MingolToken.REAL, ParseReal);
        functions.put(MingolToken.SUBSTRACTION, ParsePrefixExpressions);
        functions.put(MingolToken.NEGATION, ParsePrefixExpressions);
        functions.put(MingolToken.TRUE, ParseBoolean);
        functions.put(MingolToken.FALSE, ParseBoolean);
        functions.put(MingolToken.LPAREN, ParseGroupedExpression);
        functions.put(MingolToken.IF, ParseIf);
        functions.put(MingolToken.STRING, ParseStringLiteral);
        functions.put(MingolToken.FOR, ParseFor);
        functions.put(MingolToken.READ, ParseRead);
        functions.put(MingolToken.PRINT, ParsePrint);
        functions.put(MingolToken.GOTO, ParseGoto);
        functions.put(MingolToken.SKIP, ParseSkip);
        functions.put(MingolToken.ILLEGAL, ParseIllegal);
        functions.put(MingolToken.ALGOL, ParseAlgol);
        functions.put(MingolToken.COMMENT, ParseComments);
        functions.put(MingolToken.EOL, ParseEOL);
        return functions;
    }

    private HashMap<MingolToken, IInfixParseFn> RegisterInfixFns() {
        //Funcion
        IInfixParseFn ParseInfixExpression = new IInfixParseFn() {
            @Override
            public Expression Function(Expression expression) {
                assert currentToken != null :
                        "El current token es null";
                Infix infix = new Infix(currentToken, expression, currentToken.getLiteral());
                Precedence precedence = CurrentPrecedence();
                AdvanceToken();
                infix.setRight(ParseExpression(precedence));
                return infix;
            }
        };

        IInfixParseFn ParseCall = new IInfixParseFn() {
            @Override
            public Expression Function(Expression expression) {
                assert currentToken != null :
                        "El current token es null";
                Call call = new Call(currentToken, expression);

                call.setArguments(ParseCallArguments());

                return call;
            }
        };

        //Se agregan las funciones
        HashMap<MingolToken, IInfixParseFn> functions = new HashMap<MingolToken, IInfixParseFn>();
        functions.put(MingolToken.EQ, ParseInfixExpression);
        functions.put(MingolToken.NE, ParseInfixExpression);
        functions.put(MingolToken.LT, ParseInfixExpression);
        functions.put(MingolToken.GT, ParseInfixExpression);
        functions.put(MingolToken.LE, ParseInfixExpression);
        functions.put(MingolToken.GE, ParseInfixExpression);
        functions.put(MingolToken.ADDITION, ParseInfixExpression);
        functions.put(MingolToken.SUBSTRACTION, ParseInfixExpression);
        functions.put(MingolToken.DIVISION, ParseInfixExpression);
        functions.put(MingolToken.MULTIPLICATION, ParseInfixExpression);
        functions.put(MingolToken.LPAREN, ParseCall);
        return functions;
    }
}
