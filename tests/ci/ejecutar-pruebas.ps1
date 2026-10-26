# Ejecuta los arneses de prueba de MINGOL (lo usa GitHub Actions y también sirve en local).
# Requisitos: JDK 17 en el PATH y build\classes ya compilado (ant compile).
# Los arneses trabajan siempre en %TEMP% (nunca en el repositorio).
param(
    # Raíz del repositorio (por defecto, dos carpetas arriba de este script)
    [string]$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    # Carpeta de los logs (en GitHub Actions se pasa una dentro de runner.temp para subirlos)
    [string]$Logs = (Join-Path $env:TEMP "mingol-logs"),
    # Omite la corrida con -ea (más rápida)
    [switch]$SinEa
)

$ErrorActionPreference = "Stop"
# Los mensajes de java por stderr no deben cortar el script; se decide por el código de salida
$PSNativeCommandUseErrorActionPreference = $false

$clases = Join-Path $Repo "build\classes"
if (-not (Test-Path -LiteralPath (Join-Path $clases "Mingol\Mingol.class"))) {
    throw "No existe $clases\Mingol\Mingol.class: correr antes 'ant compile'."
}

# Carpeta de los arneses compilados y de los logs, dentro de %TEMP%
$arnes = Join-Path $env:TEMP "arnes"
$logs = $Logs
New-Item -ItemType Directory -Force $arnes, $logs | Out-Null

# Compila los arneses contra build\classes
# Área 2: se suma PruebasLexico
$fuentes = "RunTestProyecto", "PruebasArchivoErrores", "PruebasCriticos", "PruebasLexico" |
    ForEach-Object { Join-Path $Repo "tests\arneses\$_.java" }
& javac -encoding UTF-8 -nowarn -cp $clases -d $arnes @fuentes
if ($LASTEXITCODE -ne 0) { throw "No compilaron los arneses." }

$cp = "$clases;$arnes"
$resultados = @()

# Corre un modo de un arnés en su carpeta de trabajo y guarda el log
function Correr([string]$clase, [string]$modo, [string]$carpeta, [bool]$ea) {
    New-Item -ItemType Directory -Force $carpeta | Out-Null
    $nombre = "$clase-$modo" + $(if ($ea) { "-ea" } else { "" })
    $log = Join-Path $logs "$nombre.log"
    $argumentos = @()
    if ($ea) { $argumentos += "-ea" }
    $argumentos += "--enable-preview", "-cp", $cp, $clase, $modo
    Push-Location $carpeta
    try {
        & java @argumentos *> $log
        $codigo = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    # Última línea "Total: ..." del resumen
    $total = (Get-Content -LiteralPath $log | Where-Object { $_ -like "Total:*" } | Select-Object -Last 1)
    $estado = if ($codigo -eq 0) { "OK   " } else { "FALLA" }
    Write-Host "$estado $nombre  ($total)"
    if ($codigo -ne 0) {
        # Muestra las fallas para no tener que bajar el log
        $detalle = @(Get-Content -LiteralPath $log | Where-Object { $_ -match "^(FALLA|ABORTADO)|^\s+- |Exception|Error:" } |
            Select-Object -First 40)
        # Si no hay líneas de falla (p. ej. el arnés abortó), se muestran las últimas del log
        if ($detalle.Count -eq 0) { $detalle = @(Get-Content -LiteralPath $log | Select-Object -Last 20) }
        $detalle | ForEach-Object { Write-Host "      $_" }
        # En GitHub Actions, cada línea también va como anotación visible en el resumen del run
        if ($env:GITHUB_ACTIONS -eq "true") {
            $detalle | Select-Object -First 10 | ForEach-Object {
                $texto = ($_ -replace "%", "%25" -replace "`r", "" -replace "`n", " ").Trim()
                Write-Host "::error title=$nombre::$texto"
            }
        }
    }
    return [pscustomobject]@{ Nombre = $nombre; Codigo = $codigo }
}

$errores = Join-Path $env:TEMP "mingol-errores"
$criticos = Join-Path $env:TEMP "mingol-criticos"
# Carpeta de trabajo de PruebasLexico (área 2)
$lexico = Join-Path $env:TEMP "mingol-lexico"

# Área 1 (archivo de errores) y regresión de críticos. Sin 'preservacion': necesita una captura del código viejo
foreach ($modo in "ejemplos", "unitarias", "propiedades", "main") {
    $resultados += Correr "PruebasArchivoErrores" $modo $errores $false
}
foreach ($modo in "unitarias", "main") {
    $resultados += Correr "PruebasCriticos" $modo $criticos $false
}
# Área 2 (léxico). Sin 'exploracion' ni 'capturar'/'preservacion': dependen del código sin arreglar y de su captura
foreach ($modo in "unitarias", "propiedades") {
    $resultados += Correr "PruebasLexico" $modo $lexico $false
}
# Segunda pasada con aserciones (NetBeans ejecuta con -ea)
if (-not $SinEa) {
    foreach ($modo in "ejemplos", "unitarias") {
        $resultados += Correr "PruebasArchivoErrores" $modo $errores $true
    }
    $resultados += Correr "PruebasCriticos" "unitarias" $criticos $true
    # Área 2 con aserciones
    $resultados += Correr "PruebasLexico" "unitarias" $lexico $true
}

$fallidos = @($resultados | Where-Object { $_.Codigo -ne 0 })
Write-Host ""
Write-Host "Logs en $logs"
if ($fallidos.Count -gt 0) {
    Write-Host "Fallaron $($fallidos.Count) de $($resultados.Count) corridas."
    exit 1
}
Write-Host "Pasaron las $($resultados.Count) corridas."
exit 0
