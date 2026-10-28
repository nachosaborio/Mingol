# MINGOL

[![CI](https://github.com/nachosaborio/Mingol/actions/workflows/ci.yml/badge.svg)](https://github.com/nachosaborio/Mingol/actions/workflows/ci.yml)
![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-CI%2FCD-2088FF?logo=githubactions&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Apache Ant](https://img.shields.io/badge/Apache%20Ant-1.10-A81C7D?logo=apacheant&logoColor=white)
![NetBeans](https://img.shields.io/badge/NetBeans-12.6-1B6AC6?logo=apachenetbeanside&logoColor=white)
![ALGOL 68](https://img.shields.io/badge/ALGOL%2068-a68g-5C6BC0)
![Windows](https://img.shields.io/badge/Windows-CMD-0078D6?logo=windows&logoColor=white)

Compilador de **MINGOL**, un subconjunto simplificado de ALGOL 68, escrito en Java. Analiza un programa fuente `.MINGOL` (léxico, sintaxis y semántica), genera un archivo de errores con el fuente numerado y, si el programa no tiene errores, lo ejecuta con el intérprete de ALGOL 68 [`a68g`](https://jmvdveer.home.xs4all.nl/en.algol-68-genie.html).

> Proyecto académico desarrollado para la materia **03307 Compiladores** del Bachillerato en Ingeniería Informática de la **Universidad Estatal a Distancia de Costa Rica (UNED)**.

## Cómo funciona

```
java --enable-preview -jar Mingol.jar <Programa>.MINGOL
```

1. Lee el archivo fuente línea por línea.
2. Hace el análisis léxico, sintáctico y semántico.
3. Genera `<Programa>-errores.txt` en la carpeta desde donde se ejecuta, con cada línea del fuente numerada a 5 dígitos y los mensajes debajo de la línea donde ocurren:

   ```
   00002 print ( (Factor1) );
           ERROR 103: Identificador con mayúsculas [Factor1].
           ERROR 303: Identificador no encontrado [Factor1].
   00003 r := 1.;
           ERROR 109: Constante real sin dígito antes o después del punto [1.].
   ```

   Los códigos siguen el esquema 1xx léxico, 2xx sintáctico y 3xx semántico. Se reportan todos los errores de todas las líneas; en cada línea se listan primero los léxicos, después los sintácticos y al final los semánticos. Las instrucciones de ALGOL que MINGOL no soporta generan una advertencia, que no impide la ejecución.
4. Si no hay errores (las advertencias sí se permiten), copia el fuente byte a byte a `<Programa>.a68` en la carpeta desde donde se ejecuta y lo ejecuta con `a68g` en la misma consola. Si hay errores, no genera el `.a68` y borra el que haya quedado de una corrida anterior.

La extensión `.MINGOL` puede omitirse al invocarlo (se distingue sin importar mayúsculas). Si el nombre del archivo no cumple las reglas de identificador o la extensión no es `.MINGOL`, el archivo de errores termina con `ERROR 115: Nombre de archivo inválido [<nombre>].` y el programa no se ejecuta. Si el fuente no existe o no se puede leer, MINGOL lo informa en la consola (`No se encontró el archivo fuente: ...` o `No se pudo leer el archivo fuente: ...`) y no genera nada.

El código de salida es el de `a68g` cuando el programa es válido, y 1 en cualquier otro caso (uso incorrecto, fuente inexistente o ilegible, errores en el programa, `a68g` no encontrado o fallos al escribir las salidas).

## Requisitos

- JDK 17 (el proyecto se compila con `--enable-preview`).
- Apache Ant 1.10 o NetBeans 12.6 para compilar.
- `a68g` (Algol 68 Genie) para ejecutar los programas válidos. MINGOL lo busca en este orden:
  1. La ruta completa indicada en la variable de entorno `A68G`.
  2. `a68g.exe` en la misma carpeta que el `.jar`.
  3. El `PATH` del sistema.

  Por seguridad no se aceptan archivos `.bat` ni `.cmd`.

## Compilar y ejecutar

Con Ant, desde la raíz del proyecto:

```
ant compile
java --enable-preview -cp build\classes Mingol.Mingol proyecto.mingol
```

También se puede abrir como proyecto de NetBeans (`nbproject/`).

Para generar el `.jar` (queda en `dist/Mingol.jar`):

```
ant jar
java --enable-preview -jar dist\Mingol.jar proyecto.mingol
```

## Pruebas

Los arneses de `tests/arneses` se corren con un script de PowerShell (necesita JDK 17 en el `PATH` y `ant compile` hecho antes):

```
powershell -ExecutionPolicy Bypass -File tests\ci\ejecutar-pruebas.ps1
```

- `PruebasCriticos`: los errores críticos (cuelgues, excepciones, invocación de `a68g`).
- `PruebasArchivoErrores`: el formato y el orden del archivo de errores.
- `PruebasLexico`: el analizador léxico (identificadores, comentarios, constantes, longitud de línea, operadores y palabras reservadas).
- `PruebasSintaxis`: el analizador sintáctico (declaraciones, asignación, comandos de varias líneas, regla del punto y coma, `IF`/`FOR`, estructura del programa y comandos desconocidos).
- `PruebasSemantica`: el analizador semántico (variables y etiquetas definidas, `GOTO` hacia adelante, tipos en la asignación, `CHAR` de un carácter, `FOR` con variable y límites `INT`, `read` y `print`).
- `PruebasEntrada`: la entrada y la ejecución (nombre y extensión del fuente, archivo inexistente o ilegible, generación del `.a68` y código de salida de `a68g`).

Trabajan siempre en una carpeta temporal y nunca ejecutan el `a68g` real: usan `java.exe` como sustituto.

En Windows PowerShell 5.1 conviene redirigir solo la salida estándar del script (`> log`): si también se redirige la de errores, el aviso `unchecked` de `javac` lo corta.

## CI/CD

El flujo de [GitHub Actions](.github/workflows/ci.yml) corre en Windows:

- **CI:** en cada push a `main`, en cada pull request y a mano. Compila con Ant, corre los arneses (también con `-ea`), genera el `.jar`, comprueba que arranca y lo guarda como artefacto del run.
- **CD:** al subir una etiqueta `v*` (por ejemplo `git tag v1.0.0` y `git push origin v1.0.0`), publica un Release de GitHub con `Mingol.jar` adjunto.

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `src/Lexer` | Analizador léxico y definición de tokens |
| `src/Parser` | Analizador sintáctico y nodos del árbol (AST) |
| `src/Evaluador` | Análisis semántico |
| `src/Errores` | Mensajes estructurados y escritura del archivo de errores |
| `src/Mingol` | Punto de entrada, generación del `.a68` e invocación de `a68g` |
| `tests/arneses` | Arneses de prueba (sin JUnit) |
| `tests/ci` | Script que corre los arneses (lo usa el CI) |
| `.github/workflows` | Flujo de CI/CD de GitHub Actions |

## Estado

Están hechas las correcciones de los errores críticos, el formato del archivo de errores, el analizador léxico, el analizador sintáctico (declaraciones, asignación, comandos de varias líneas, regla del punto y coma, `IF`/`FOR`, estructura del programa y comandos desconocidos) y el analizador semántico (variables y etiquetas definidas, `GOTO` a una etiqueta de cualquier parte del programa, tipos en la asignación, `CHAR` de un solo carácter, `FOR` con variable y límites `INT`, y `read`/`print`), además de la entrada y la ejecución (validación del nombre del archivo, `.a68` solo sin errores y código de salida de `a68g`). El programa de ejemplo `proyecto.mingol` ya pasa los análisis léxico, sintáctico y semántico: lo único que impide su ejecución son tres textos de más de 30 caracteres (`ERROR 111`), como exige el requisito de constantes de texto.

## Créditos

Desarrollado por Sergio Ignacio Saborío Segura para el curso 03307 Compiladores, Escuela de Ciencias Exactas y Naturales, UNED Costa Rica (primer cuatrimestre de 2022).