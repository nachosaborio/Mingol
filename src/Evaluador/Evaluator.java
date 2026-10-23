package Evaluador;

import Parser.Block;
import Parser.Booleano;
import Parser.Decimal;
import Parser.Etiqueta;
import Parser.Expression;
import Parser.ExpressionStatement;
import Parser.Goto;
import Parser.Identifier;
import Parser.If;
import Parser.Infix;
import Parser.Integral;
import Parser.Interfaces.ASTNode;
import Parser.LetStatement;
import Parser.Prefix;
import Parser.Print;
import Parser.Program;
import Parser.Statement;
import Parser.StringLiteral;
import Errores.Mensaje;
import java.util.ArrayList;

public class Evaluator {

    public static boolean hasErrors = false;
    // Mensajes estructurados del evaluador para el archivo de errores (único sitio de registro: NewError)
    private static ArrayList<Mensaje> mensajes = new ArrayList<>();
    public static final Logico TRUE = new Logico(true);
    public static final Logico FALSE = new Logico(false);
    public static final Nulo NULL = new Nulo();
    private static String TypeMismatchError = "\tError 301: Discrepancia de tipos:";
    private static String UnknownPrefixOperation = "\tError 302: Operador inválido:";
    private static String UnknownIdentifier = "\tError 303: Identificador no encontrado:";
    private static String RepeatedIdentificator = "\tError 304: Identificador ya declarado:";
    private static String AlreadyBegin = "\tError 305: No puede haber más de una sentencia BEGIN por archivo.";
    // END repetido usa 306 (antes repetía el 305 de BEGIN)
    private static String AlreadyEnded = "\tError 306: No puede haber más de una sentencia END por archivo.";
    private static String NotBegined = "\tError 307: No se encontró una sentencia BEGIN.";
    private static String NotEnded = "\tError 308: No se encontró una sentencia END.";

    // Mensajes registrados por el evaluador desde el último Reiniciar()
    public static ArrayList<Mensaje> GetMensajes() {
        return mensajes;
    }

    // Deja el estado estático limpio para analizar otro programa en la misma JVM
    public static void Reiniciar() {
        mensajes.clear();
        hasErrors = false;
    }

    public static Objeto Evaluate(ASTNode node, Environment env) {
        // El parser pudo dejar nodos incompletos (null): no hay nada que evaluar
        if (node == null) {
            return null;
        }
        String className = node.getClass().getSimpleName();

        switch (className) {
            case "Program":
                Program program = (Program) node;
                Objeto evaluado = EvaluateProgram(program, env);
                // Tras recorrer todo el programa: 307 y/o 308 solo si de verdad falta BEGIN/END
                Errado faltante = null;
                if(env.get("BEGIN") == null){
                    faltante = NewError(Mensaje.SIN_LINEA, 307, "No se encontró una sentencia BEGIN", "BEGIN",
                            NotBegined, new String[]{});
                }
                if(env.get("END") == null){
                    Errado sinEnd = NewError(Mensaje.SIN_LINEA, 308, "No se encontró una sentencia END", "END",
                            NotEnded, new String[]{});
                    if (faltante == null) {
                        faltante = sinEnd;
                    }
                }
                // Se devuelve el primero de los faltantes; si no hay, lo evaluado
                return faltante != null ? faltante : evaluado;
            case "Statement":
                Statement statement = (Statement) node;
                return EvaluateStatement(statement, env);
            case "ExpressionStatement":
                ExpressionStatement expression = (ExpressionStatement) node;
                // Expresión null del parser (p. ej. IF sin FI): su error ya se registró
                if (expression.getExpression() == null) {
                    return null;
                }
                assert expression.getExpression() != null :
                        "La expresion es nula";
                return Evaluate(expression.getExpression(), env);
            case "Integral":
                Integral integral = (Integral) node;
                assert integral != null :
                        "El entero es nulo";
                return new Entero(integral.getValue());
            case "Decimal":
                Decimal real = (Decimal) node;
                assert real != null :
                        "El real es nulo";
                return new Real(real.getValue());
            case "Booleano":
                Booleano logico = (Booleano) node;
                assert logico != null :
                        "El booleano es nulo";
                return ToBooleanObject(logico.getValue());
            case "Prefix":
                Prefix prefix = (Prefix) node;
                // Operando null del parser: su error ya se registró, sin error nuevo
                if (prefix.getRight() == null) {
                    return null;
                }
                assert prefix.getRight() != null :
                        "La derecha es nula";
                Objeto right = Evaluate(prefix.getRight(), env);
                // Un Errado del operando se propaga sin crear otro; un resultado null no se evalúa
                if (right == null || right instanceof Errado) {
                    return right;
                }
                assert right != null :
                        "Right es nulo";
                return EvaluatePrefixExpression(prefix, prefix.getOperator(), right);
            case "Infix":
                Infix infix = (Infix) node;
                // Se evalúan los dos lados (Evaluate(null) da null) para registrar los errores de ambos
                Objeto left = Evaluate(infix.getLeft(), env);
                Objeto iright = Evaluate(infix.getRight(), env);
                // Un Errado del operando se propaga sin crear un 301 en cascada
                if (left instanceof Errado) {
                    return left;
                }
                if (iright instanceof Errado) {
                    return iright;
                }
                // Operando null (parser o código no pedido): null sin error
                if (left == null || iright == null) {
                    return null;
                }
                assert infix.getLeft() != null :
                        "La izquierda es nula";
                assert infix.getRight() != null :
                        "La derecha es nula";
                assert left != null :
                        "La izquierda es nula";
                assert iright != null :
                        "La derecha es nula";
                return EvaluateInfixExpression(infix, infix.getOperator(), left, iright);
            case "Block":
                Block block = (Block) node;
                return EvaluateStatements(block.getStatements(), env);
            case "If":
                If si = (If) node;
                return EvaluateIfExpression(si, env);
            case "LetStatement":
                LetStatement let = (LetStatement) node;
                assert let != null :
                        "El identificador es nulo";
                Objeto value = Evaluate(let.getValue(), env);
                assert let.getName() != null :
                        "El nombre del identificador es nulo";
                // El parser pudo dejar el valor en null: la variable cuenta igual como declarada
                if (value == null) {
                    value = NULL;
                }
                if (env.get(let.getName().getValue()) == null) {
                    // Se guarda aunque sea un Errado: la variable cuenta como declarada (sin 303 en cascada)
                    env.put(let.getName().getValue(), value);
                } else {
                    Errado repetido = NewError(let.getName().getLine(), 304, "Identificador ya declarado",
                            let.getName().getValue(), RepeatedIdentificator,
                            new String[]{let.getName().getValue()});
                    // Se devuelve el primer error de la sentencia (el del valor, si lo hubo)
                    return (value instanceof Errado) ? value : repetido;
                }
                // El error del valor inicial ya no se descarta: se devuelve para que EvaluateProgram lo vea
                if (value instanceof Errado) {
                    return value;
                }
                break;
            case "Identifier":
                Identifier identifier = (Identifier) node;
                return EvaluateIdentifier(identifier, env);
            case "Call":
                break;
            case "StringLiteral":
                StringLiteral str = (StringLiteral) node;
                return new Cadena(str.getValue());
            case "Etiqueta":
                Etiqueta etiqueta = (Etiqueta) node;
                if (env.get(etiqueta.getValue()) == null) {
                    env.put(etiqueta.getValue(), "");
                } else {
                    // 304 con la línea de la etiqueta
                    return NewError(etiqueta.getLine(), 304, "Identificador ya declarado", etiqueta.getValue(),
                            RepeatedIdentificator, new String[]{etiqueta.getValue()});
                }
                break;
            case "Goto":
                Goto gouto = (Goto) node;
                if(env.get(gouto.getIdentificador().TokenLiteral()) == null) {
                    // 303 con la línea del identificador del GOTO
                    return NewError(gouto.getIdentificador().getLine(), 303, "Identificador no encontrado",
                            gouto.getIdentificador().TokenLiteral(), UnknownIdentifier,
                            new String[]{gouto.getIdentificador().toString()});
                }
                break;
            case "Print":
                Print print = (Print) node;
                // Se registran todos los identificadores no definidos y se devuelve el primero
                Errado primeroPrint = null;
                if(print.getIdentificadores().size() >0){
                    for(Expression identificador : print.getIdentificadores()){
                        if(env.get(identificador.TokenLiteral()) == null){
                            Errado noDefinido = NewError(identificador.getLine(), 303, "Identificador no encontrado",
                                    identificador.TokenLiteral(), UnknownIdentifier,
                                    new String[]{identificador.TokenLiteral()});
                            if (primeroPrint == null) {
                                primeroPrint = noDefinido;
                            }
                        }
                    }
                }
                if (primeroPrint != null) {
                    return primeroPrint;
                }
        }
        return null;
    }

    private static Objeto EvaluateBangOperatorExpression(Objeto right) {
        if (right == TRUE) {
            return FALSE;
        } else if (right == FALSE) {
            return TRUE;
        } else if (right == NULL) {
            return TRUE;
        } else {
            return FALSE;
        }
    }

    private static Objeto EvaluateIdentifier(Identifier identifier, Environment env) {
        // Solo las variables (Objeto) se resuelven; etiquetas y marcas BEGIN/END cuentan como no definidas
        Object guardado = env.get(identifier.getValue());
        if (!(guardado instanceof Objeto)) {
            // Nueva firma: 303 con la línea del identificador
            return NewError(identifier.getLine(), 303, "Identificador no encontrado", identifier.getValue(),
                    UnknownIdentifier, new String[]{identifier.getValue()});
        }
        return (Objeto) guardado;
    }

    private static Objeto EvaluateIfExpression(If si, Environment env) {
        assert si != null :
                "El if es nulo";
        Objeto condition = Evaluate(si.getCondition(), env);
        // Condición null (parser) o Errado (ya registrado): sin error nuevo; IsTruthy elige la consecuencia como hoy
        if (condition != null) {
            assert condition != null :
                    "La condicion es nula";
        }

        if (IsTruthy(condition)) {
            // Consecuencia null del parser: no hay nada que evaluar
            if (si.getConsequence() == null) {
                return null;
            }
            assert si.getConsequence() != null :
                    "La consecuencia es null";
            return Evaluate(si.getConsequence(), env);
        } else if (si.getAlternative() != null) {
            return Evaluate(si.getAlternative(), env);
        } else {
            return NULL;
        }
    }

    // Se recibe el nodo Infix para dar la línea a los 301/302
    private static Objeto EvaluateInfixExpression(Infix nodo, String operator, Objeto left, Objeto right) {
        if (left.Type() == ObjectType.INTEGERS && right.Type() == ObjectType.INTEGERS) {
            return EvaluateIntegerInfixExpression(nodo, operator, left, right);
        } else if (operator.equals("=") || operator.equals("EQ")) {
            return ToBooleanObject(left == right);
        } else if (operator.equals("/=") || operator.equals("NE")) {
            return ToBooleanObject(left != right);
        } else if (left.Type() != right.Type()) {
            String[] args = new String[]{left.Type().toString(), operator, right.Type().toString()};
            return NewError(nodo.getLine(), 301, "Discrepancia de tipos: " + String.join(" ", args), operator,
                    TypeMismatchError, args);
        } else {
            String[] args = new String[]{left.Type().toString(), operator, right.Type().toString()};
            return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), operator,
                    UnknownPrefixOperation, args);
        }
    }

    // Se recibe el nodo Infix para dar la línea al 302
    private static Objeto EvaluateIntegerInfixExpression(Infix nodo, String operator, Objeto left, Objeto right) {
        int leftValue = ((Entero) left).getValue();
        int rigthValue = ((Entero) right).getValue();

        switch (operator) {
            case "+":
                return new Entero(leftValue + rigthValue);
            case "-":
                return new Entero(leftValue - rigthValue);
            case "*":
                return new Entero(leftValue * rigthValue);
            case "/":
                return new Entero(leftValue / rigthValue);
            case "LT":
            case "<":
                return ToBooleanObject(leftValue < rigthValue);
            case "GT":
            case ">":
                return ToBooleanObject(leftValue > rigthValue);
            case "EQ":
            case "=":
                return ToBooleanObject(leftValue == rigthValue);
            case "NE":
            case "/=":
                return ToBooleanObject(leftValue != rigthValue);
            case "GE":
            case ">=":
                return ToBooleanObject(leftValue >= rigthValue);
            case "LE":
            case "<=":
                return ToBooleanObject(leftValue <= rigthValue);
            default:
                String[] args = new String[]{left.Type().toString(), operator, right.Type().toString()};
                return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), operator,
                        UnknownPrefixOperation, args);
        }
    }

    // Se recibe el nodo Prefix para dar la línea al 302
    private static Objeto EvaluateMinusOperatorExpression(Prefix nodo, Objeto right) {
        if (!(right instanceof Entero)) {
            String[] args = new String[]{"-", right.Type().toString()};
            return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), "-",
                    UnknownPrefixOperation, args);
        } else {
            Entero entero = (Entero) right;
            return new Entero(-entero.getValue());
        }
    }

    // Se recibe el nodo Prefix para dar la línea al 302
    private static Objeto EvaluatePrefixExpression(Prefix nodo, String operator, Objeto right) {

        switch (operator) {
            case "!":
                return EvaluateBangOperatorExpression(right);
            case "-":
                return EvaluateMinusOperatorExpression(nodo, right);
            default:
                String[] args = new String[]{operator, right.Type().toString()};
                return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), operator,
                        UnknownPrefixOperation, args);
        }
    }

    private static Objeto EvaluateProgram(Program program, Environment env) {
        Objeto result = null;
        Objeto primerError = null;
        // Ya no corta en el primer Errado: recorre todo (BEGIN/END llegan a env) y devuelve el primero
        for (Statement statement : program.statements) {
            result = Evaluate(statement, env);
            if (result instanceof Errado && primerError == null) {
                primerError = result;
            }
        }
        return primerError != null ? primerError : result;
    }

    private static Objeto EvaluateStatement(Statement statement, Environment env) {
        switch (statement.TokenLiteral()) {
            case "BEGIN":
                if (env.get("BEGIN") == null) {
                    env.put("BEGIN", "BEGIN");
                } else {
                    // 305 con la línea del BEGIN repetido
                    return NewError(statement.getLine(), 305, "No puede haber más de una sentencia BEGIN por archivo",
                            "BEGIN", AlreadyBegin, new String[]{});
                }
                break;
            case "END":
                if (env.get("END") == null) {
                    env.put("END", "END");
                } else {
                    // 306 con la línea del END repetido
                    return NewError(statement.getLine(), 306, "No puede haber más de una sentencia END por archivo",
                            "END", AlreadyEnded, new String[]{});
                }
                break;
        }
        return null;
    }

    private static Objeto EvaluateStatements(ArrayList<Statement> statements, Environment env) {
        Objeto result = null;
        for (Statement statement : statements) {
            result = Evaluate(statement, env);
        }
        return result;
    }

    private static boolean IsTruthy(Objeto obj) {
        if (obj == NULL) {
            return false;
        } else if (obj == TRUE) {
            return true;
        } else if (obj == FALSE) {
            return false;
        }
        return true;
    }

    // Arma el mismo Errado que antes (mismo Inspect()) y registra su Mensaje; único sitio de registro
    private static Errado NewError(int linea, int codigo, String texto, String lexema, String message, String[] args) {
        hasErrors = true;
        mensajes.add(Mensaje.Error(linea, codigo, texto, lexema));
        for (Object obj : args) {
            message += " " + obj.toString();
        }
        return new Errado(message);
    }

    private static Logico ToBooleanObject(boolean value) {
        return value ? TRUE : FALSE;
    }
}